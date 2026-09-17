package com.calles.platform.transcode.infrastructure.engine;

import com.calles.platform.transcode.config.TranscodeProperties;
import com.calles.platform.transcode.domain.engine.TranscodeEngine;
import com.calles.platform.transcode.domain.engine.TranscodeResult;
import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.exception.TranscodeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于宿主机 FFmpeg/FFprobe 二进制工具的高性能视频转码压制执行引擎 (FfmpegTranscodeEngine)。
 *
 * <p>核心能力与防护策略：
 * <ul>
 *   <li><b>等比保真与黑边填充</b>：采用 scale + pad 滤镜，确保源视频在任意宽高比下不失真变形并补齐目标画质分辨率；</li>
 *   <li><b>Web 边下边播加速</b>：通过 {@code -movflags +faststart} 将 MP4 moov atom 元数据移至文件头部，保障前端点播秒开；</li>
 *   <li><b>硬件安全受控</b>：默认限制线程数为 2，避免突发高负载压垮宿主机 CPU 与内存；</li>
 *   <li><b>死锁与超时防御</b>：异步消费 stdout/stderr 流缓冲，设置超时强制终止 (destroyForcibly) 兜底。</li>
 * </ul>
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class FfmpegTranscodeEngine implements TranscodeEngine {

    /** 转码配置参数对象。 */
    private final TranscodeProperties properties;

    @Override
    public String engineType() {
        return "FFMPEG";
    }

    @Override
    public TranscodeResult transcode(File sourceFile, QualityPreset preset, File workDir) throws TranscodeException {
        // 步骤 1：前置参数与工作环境校验
        if (sourceFile == null || !sourceFile.exists() || sourceFile.length() == 0) {
            throw new TranscodeException("待转码原始文件不存在或内容为空");
        }
        if (workDir == null || (!workDir.exists() && !workDir.mkdirs())) {
            throw new TranscodeException("转码工作临时目录无法创建: " + workDir);
        }

        File outputFile = new File(workDir, "transcode_" + preset.name().toLowerCase() + ".mp4");
        List<String> command = buildFfmpegCommand(sourceFile, outputFile, preset);

        log.info("启动 FFmpeg 转码压制: preset={}, source={}, output={}", preset.name(), sourceFile.getName(), outputFile.getName());
        long startTime = System.currentTimeMillis();
        List<String> processLogs = new ArrayList<>();

        // 步骤 2：通过 ProcessBuilder 驱动本地 FFmpeg 进程并安全消费标准输出日志
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();

            // 异步消费子进程日志，防止操作系统管道缓冲区填满导致进程死锁挂起
            Process finalProcess = process;
            Thread logConsumerThread = Thread.ofVirtual().name("ffmpeg-log-" + preset.name()).start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (processLogs) {
                            if (processLogs.size() < 200) {
                                processLogs.add(line);
                            } else {
                                processLogs.remove(0);
                                processLogs.add(line);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            });

            // 步骤 3：带超时时限等待进程执行结束
            int timeout = properties.getTaskTimeoutSeconds() > 0 ? properties.getTaskTimeoutSeconds() : 600;
            boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
            logConsumerThread.join(1000);

            if (!completed) {
                process.destroyForcibly();
                log.error("FFmpeg 转码超时，已被强制终止: preset={}, timeout={}s", preset.name(), timeout);
                throw new TranscodeException("FFmpeg 转码任务超时（超过 " + timeout + " 秒）");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String errorTail = String.join("\n", processLogs);
                log.error("FFmpeg 转码执行失败: exitCode={}, error={}", exitCode, errorTail);
                throw new TranscodeException("FFmpeg 转码失败，退出码: " + exitCode + ", 错误输出: " + errorTail);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new TranscodeException("转码线程被意外中断", e);
        } catch (TranscodeException e) {
            throw e;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw new TranscodeException("FFmpeg 进程启动或执行异常: " + e.getMessage(), e);
        }

        long costMs = System.currentTimeMillis() - startTime;
        if (!outputFile.exists() || outputFile.length() == 0) {
            throw new TranscodeException("FFmpeg 已退出但未产生有效的产物文件");
        }

        log.info("FFmpeg 转码压制完成: preset={}, cost={}ms, size={} bytes", preset.name(), costMs, outputFile.length());

        // 步骤 4：通过 FFprobe 探测产物视频实际元数据（时长、码率、宽高、帧率）
        ProbeResult probe = probeMedia(outputFile, preset);

        return TranscodeResult.builder()
                .outputFile(outputFile)
                .fileSize(outputFile.length())
                .bitrate(probe.bitrate > 0 ? probe.bitrate : preset.getTargetBitrate())
                .fps(probe.fps > 0 ? probe.fps : preset.getTargetFps())
                .width(probe.width > 0 ? probe.width : preset.getTargetWidth())
                .height(probe.height > 0 ? probe.height : preset.getTargetHeight())
                .duration(probe.duration > 0 ? probe.duration : 0)
                .transcodeCostMs(costMs)
                .build();
    }

    /**
     * 拼装 FFmpeg 命令行参数列表。
     */
    private List<String> buildFfmpegCommand(File sourceFile, File outputFile, QualityPreset preset) {
        List<String> cmd = new ArrayList<>();
        cmd.add(properties.getFfmpegPath());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(sourceFile.getAbsolutePath());

        // 视频等比缩放滤镜：保持宽高比缩小，并补黑边至目标分辨率
        String filter = String.format(
                "scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2",
                preset.getTargetWidth(), preset.getTargetHeight(),
                preset.getTargetWidth(), preset.getTargetHeight()
        );
        cmd.add("-vf");
        cmd.add(filter);

        // 视频编码与比特率控制
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-b:v");
        cmd.add(preset.getTargetBitrate() + "k");
        cmd.add("-maxrate");
        cmd.add((int) (preset.getTargetBitrate() * 1.5) + "k");
        cmd.add("-bufsize");
        cmd.add((preset.getTargetBitrate() * 2) + "k");
        cmd.add("-r");
        cmd.add(String.valueOf(preset.getTargetFps()));

        // 音频编码
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");

        // 硬件保护与 Web 边下边播头部优化
        cmd.add("-threads");
        cmd.add("2");
        cmd.add("-movflags");
        cmd.add("+faststart");

        cmd.add(outputFile.getAbsolutePath());
        return cmd;
    }

    /**
     * 调用 FFprobe 工具提取视频时长与基础信息。
     */
    private ProbeResult probeMedia(File file, QualityPreset preset) {
        ProbeResult result = new ProbeResult();
        try {
            List<String> cmd = List.of(
                    properties.getFfprobePath(),
                    "-v", "error",
                    "-show_entries", "format=duration,bit_rate:stream=width,height,r_frame_rate",
                    "-of", "default=noprint_wrappers=1:nokey=0",
                    file.getAbsolutePath()
            );
            Process process = new ProcessBuilder(cmd).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("=", 2);
                    if (parts.length != 2) continue;
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    switch (key) {
                        case "duration" -> {
                            try {
                                result.duration = (int) Math.round(Double.parseDouble(value));
                            } catch (Exception ignored) {}
                        }
                        case "bit_rate" -> {
                            try {
                                result.bitrate = (int) (Long.parseLong(value) / 1000);
                            } catch (Exception ignored) {}
                        }
                        case "width" -> {
                            try {
                                if (result.width == 0) result.width = Integer.parseInt(value);
                            } catch (Exception ignored) {}
                        }
                        case "height" -> {
                            try {
                                if (result.height == 0) result.height = Integer.parseInt(value);
                            } catch (Exception ignored) {}
                        }
                        case "r_frame_rate" -> {
                            try {
                                if (result.fps == 0 && value.contains("/")) {
                                    String[] frac = value.split("/");
                                    double num = Double.parseDouble(frac[0]);
                                    double den = Double.parseDouble(frac[1]);
                                    if (den > 0) result.fps = (int) Math.round(num / den);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("FFprobe 提取媒体元数据发生警告，降级使用默认预设值: file={}, error={}", file.getName(), e.getMessage());
        }
        return result;
    }

    private static class ProbeResult {
        int duration = 0;
        int bitrate = 0;
        int fps = 0;
        int width = 0;
        int height = 0;
    }
}
