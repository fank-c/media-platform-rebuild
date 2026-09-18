package com.calles.platform.transcode.infrastructure.engine;

import com.calles.platform.transcode.domain.engine.TranscodeEngine;
import com.calles.platform.transcode.domain.engine.TranscodeResult;
import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.exception.TranscodeException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 模拟桩视频转码执行引擎 (MockTranscodeEngine)。
 *
 * <p>用于本地开发脱网环境、CI/CD 自动化集成测试或无系统 FFmpeg 依赖的环境下，
 * 极速生成符合目标规格约定的模拟媒体切片产物及元数据，验证全流水线闭环。</p>
 */
@Slf4j
public class MockTranscodeEngine implements TranscodeEngine {

    @Override
    public String engineType() {
        return "MOCK";
    }

    @Override
    public TranscodeResult transcode(File sourceFile, QualityPreset preset, File workDir) throws TranscodeException {
        // 步骤 1：检查工作目录
        if (workDir == null || (!workDir.exists() && !workDir.mkdirs())) {
            throw new TranscodeException("模拟转码目录不存在且创建失败: " + workDir);
        }

        File outputFile = new File(workDir, "transcode_mock_" + preset.getCode().toLowerCase() + ".mp4");
        long start = System.currentTimeMillis();

        // 步骤 2：生成模拟切片物理文件（若源文件存在且非空则复制，否则填充模拟二进制数据）
        try {
            if (sourceFile != null && sourceFile.exists() && sourceFile.length() > 0) {
                Files.copy(sourceFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] mockBytes = ("MOCK_TRANSCODE_DATA_" + preset.getCode()).getBytes();
                    fos.write(mockBytes);
                }
            }
        } catch (IOException e) {
            throw new TranscodeException("模拟切片生成失败: " + e.getMessage(), e);
        }

        long cost = System.currentTimeMillis() - start;
        log.info("Mock 转码模拟完成: preset={}, output={}, size={} bytes", preset.getCode(), outputFile.getName(), outputFile.length());

        // 步骤 3：直接构筑符合画质预设标准的媒体元数据返回
        return TranscodeResult.builder()
                .outputFile(outputFile)
                .fileSize(outputFile.length())
                .bitrate(preset.getTargetBitrate())
                .fps(preset.getTargetFps())
                .width(preset.getTargetWidth())
                .height(preset.getTargetHeight())
                .duration(120) // 模拟默认时长 120 秒
                .transcodeCostMs(Math.max(cost, 10))
                .build();
    }
}
