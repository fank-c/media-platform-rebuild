package com.calles.platform.transcode.application.service;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.transcode.config.TranscodeProperties;
import com.calles.platform.transcode.domain.engine.TranscodeEngine;
import com.calles.platform.transcode.domain.engine.TranscodeResult;
import com.calles.platform.transcode.domain.model.MediaCodec;
import com.calles.platform.transcode.domain.model.MediaFormat;
import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.domain.model.TranscodeTask;
import com.calles.platform.transcode.domain.model.TranscodeTaskStatus;
import com.calles.platform.transcode.domain.repository.TranscodeTaskRepository;
import com.calles.platform.transcode.exception.TranscodeException;
import com.calles.platform.transcode.application.client.ContentServiceClient;
import com.calles.platform.transcode.application.client.FileServiceClient;
import com.calles.platform.transcode.application.client.FileSystemMultipartFile;
import com.calles.platform.transcode.application.client.dto.ContentServiceDTOs;
import com.calles.platform.transcode.application.client.dto.FileServiceDTOs;
import com.calles.platform.transcode.infrastructure.concurrency.TranscodeRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * 视频转码业务全流程综合编排应用服务 (TranscodeApplicationService)。
 *
 * <p>核心编排流水线：
 * <ol>
 *   <li><b>幂等检查与任务建单</b>：校验针对该视频与规格是否已有终态任务，避免重复计算；</li>
 *   <li><b>源片安全拉取</b>：通过 Feign 向 file-service 申请直链并流式拉取原片至本地沙箱工作区；</li>
 *   <li><b>并发限流压制</b>：在硬件保护限流器管控下驱动引擎（FFmpeg/Mock）完成多清晰度切片转码；</li>
 *   <li><b>切片资产托管</b>：将切片产物直传注册至 file-service 并签发全新文件资产 ID；</li>
 *   <li><b>发布门禁回调</b>：向 content-service 登记转码流规格与时长，驱动视频分级发布门禁闭环；</li>
 *   <li><b>沙箱资源自愈清理</b>：finally 保障彻底清除本地所有临时音视频大文件，杜绝磁盘泄漏。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeApplicationService {

    private final TranscodeTaskRepository taskRepository;
    private final TranscodeEngine transcodeEngine;
    private final TranscodeRateLimiter rateLimiter;
    private final FileServiceClient fileServiceClient;
    private final ContentServiceClient contentServiceClient;
    private final TranscodeProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 执行单项画质规格的转码压制工作流。
     *
     * @param videoId 关联的视频全局 ID
     * @param authorId 创作者账号 ID
     * @param sourceFileId 原始视频文件资产 ID
     * @param preset 目标清晰度规格 (如 P720)
     * @return 最终转码任务领域实体
     */
    public TranscodeTask processTask(
            String videoId,
            String authorId,
            String sourceFileId,
            QualityPreset preset
    ) {
        long pipelineStartTime = System.currentTimeMillis();
        String taskType = "TRANSCODE_" + preset.name();

        // 步骤 1：幂等校验与工单初始化
        Optional<TranscodeTask> existingOpt = taskRepository.findBySpec(videoId, preset, MediaFormat.MP4);
        TranscodeTask task;
        if (existingOpt.isPresent()) {
            task = existingOpt.get();
            if (task.getStatus() == TranscodeTaskStatus.COMPLETED) {
                log.info("转码任务已处于完成态，命中幂等跳过: videoId={}, preset={}, outputFileId={}",
                        videoId, preset.name(), task.getOutputFileId());
                return task;
            }
            log.info("转码任务已存在且未完成，重置状态重新执行: taskId={}, status={}", task.getId(), task.getStatus());
            task.setStatus(TranscodeTaskStatus.PENDING);
            taskRepository.updateById(task);
        } else {
            task = TranscodeTask.create(videoId, authorId, sourceFileId, preset, MediaFormat.MP4, MediaCodec.H264);
            taskRepository.insert(task);
            log.info("创建全新转码任务工单: taskId={}, videoId={}, preset={}", task.getId(), videoId, preset.name());
        }

        File workDir = new File(properties.getWorkDir(), task.getId());

        try {
            if (!workDir.exists() && !workDir.mkdirs()) {
                throw new TranscodeException("无法初始化任务沙箱工作目录: " + workDir.getAbsolutePath());
            }

            // 步骤 2：流式拉取原片到本地沙箱工作区
            task.markDownloading();
            taskRepository.updateById(task);
            reportTaskProgress(videoId, taskType, "RUNNING", 10, null);

            File sourceFile = downloadSourceFile(task.getSourceFileId(), workDir);
            log.info("原始视频原片拉取成功: taskId={}, sourceSize={} bytes", task.getId(), sourceFile.length());

            // 步骤 3：在硬件保护限流器保护下调用引擎压制转码
            task.markTranscoding();
            taskRepository.updateById(task);
            reportTaskProgress(videoId, taskType, "RUNNING", 30, null);

            TranscodeResult result = rateLimiter.executeWithPermit(() ->
                    transcodeEngine.transcode(sourceFile, preset, workDir));

            // 步骤 4：上传转码产物至文件微服务
            task.markUploading(result.getTranscodeCostMs());
            taskRepository.updateById(task);
            reportTaskProgress(videoId, taskType, "RUNNING", 70, null);

            String outputFileId = uploadOutputFile(result.getOutputFile(), authorId);
            log.info("转码切片成功上传至文件服务: taskId={}, outputFileId={}", task.getId(), outputFileId);

            // 步骤 5：回调内容服务，完成流媒体产物登记与门禁打标
            task.markNotifying(outputFileId, result.getFileSize());
            taskRepository.updateById(task);
            reportTaskProgress(videoId, taskType, "RUNNING", 90, null);

            notifyContentService(videoId, preset, outputFileId, result);

            // 步骤 6：终态成功跃迁
            long totalCost = System.currentTimeMillis() - pipelineStartTime;
            task.complete(
                    outputFileId,
                    result.getFileSize(),
                    result.getBitrate(),
                    result.getFps(),
                    result.getWidth(),
                    result.getHeight(),
                    result.getDuration(),
                    totalCost
            );
            taskRepository.updateById(task);
            reportTaskProgress(videoId, taskType, "SUCCESS", 100, null);

            log.info("转码全流水线闭环成功: taskId={}, videoId={}, preset={}, totalCost={}ms",
                    task.getId(), videoId, preset.name(), totalCost);
            return task;

        } catch (Exception e) {
            long totalCost = System.currentTimeMillis() - pipelineStartTime;
            log.error("转码流水线处理异常: taskId={}, videoId={}, preset={}, error={}",
                    task.getId(), videoId, preset.name(), e.getMessage(), e);

            task.fail(e.getMessage(), totalCost);
            taskRepository.updateById(task);
            reportTaskProgress(videoId, taskType, "FAILED", null, e.getMessage());

            throw (e instanceof TranscodeException te ? te : new TranscodeException("转码流水线失败: " + e.getMessage(), e));
        } finally {
            // 步骤 7：强力资源自愈清理，彻底删除任务专属沙箱目录
            try {
                if (workDir.exists()) {
                    FileSystemUtils.deleteRecursively(workDir);
                    log.debug("清理任务临时工作目录完成: workDir={}", workDir.getAbsolutePath());
                }
            } catch (Exception e) {
                log.warn("清理任务沙箱目录异常: workDir={}, error={}", workDir.getAbsolutePath(), e.getMessage());
            }
        }
    }

    /**
     * 下载原始待转码文件至任务工作目录。
     */
    private File downloadSourceFile(String sourceFileId, File workDir) {
        ApiResponse<FileServiceDTOs.DownloadUrlResponse> urlResponse =
                fileServiceClient.getInternalDownloadUrl(sourceFileId);
        if (urlResponse == null || urlResponse.data() == null || urlResponse.data().url() == null) {
            throw new TranscodeException("获取原始文件下载直链失败，文件可能不存在或已过期: " + sourceFileId);
        }

        String downloadUrl = urlResponse.data().url();
        File sourceFile = new File(workDir, "source_original.mp4");

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<java.nio.file.Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(sourceFile.toPath()));
            if (response.statusCode() >= 400 || !sourceFile.exists() || sourceFile.length() == 0) {
                throw new TranscodeException("下载原始文件 HTTP 状态异常: code=" + response.statusCode());
            }
            return sourceFile;
        } catch (Exception e) {
            throw new TranscodeException("流式拉取原始视频失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将本地转码生成的切片通过 Feign Multipart 托管上传至 file-service。
     */
    private String uploadOutputFile(File outputFile, String authorId) {
        if (!outputFile.exists() || outputFile.length() == 0) {
            throw new TranscodeException("转码产物文件不存在或内容为空，无法上传: " + outputFile.getName());
        }

        FileSystemMultipartFile multipartFile = new FileSystemMultipartFile(outputFile, "video/mp4");
        ApiResponse<FileServiceDTOs.FileUploadResponse> uploadResponse =
                fileServiceClient.uploadInternal(multipartFile, authorId, "MINIO");

        if (uploadResponse == null || uploadResponse.data() == null || uploadResponse.data().fileId() == null) {
            throw new TranscodeException("内部上传转码产物失败，文件微服务未返回有效 fileId");
        }
        return uploadResponse.data().fileId();
    }

    /**
     * 带轻量重试机制通知 content-service 登记转码流与时长。
     */
    private void notifyContentService(String videoId, QualityPreset preset, String outputFileId, TranscodeResult result) {
        ContentServiceDTOs.TranscodeCallbackRequest request = new ContentServiceDTOs.TranscodeCallbackRequest(
                videoId,
                preset.name(),
                MediaFormat.MP4.name(),
                MediaCodec.H264.name(),
                outputFileId,
                result.getFileSize(),
                result.getBitrate(),
                result.getFps(),
                "COMPLETED",
                result.getDuration()
        );

        int maxRetries = Math.max(properties.getCallbackMaxRetries(), 3);
        Exception lastEx = null;

        for (int i = 1; i <= maxRetries; i++) {
            try {
                ApiResponse<Void> response = contentServiceClient.transcodeCallback(request);
                if (response != null && response.code() == 200) {
                    log.info("转码回调通知 content-service 成功: videoId={}, preset={}", videoId, preset.name());
                    return;
                }
                log.warn("转码回调 content-service 返回非200状态: code={}, retryCount={}/{}",
                        response != null ? response.code() : "null", i, maxRetries);
            } catch (Exception e) {
                lastEx = e;
                log.warn("转码回调 content-service 发生异常: error={}, retryCount={}/{}", e.getMessage(), i, maxRetries);
            }

            try {
                Thread.sleep(Math.min(100L * (1L << (i - 1)), 2000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        throw new TranscodeException("重试 " + maxRetries + " 次后通知 content-service 失败: "
                + (lastEx != null ? lastEx.getMessage() : "未知错误"), lastEx);
    }

    /**
     * 向上游流水线异步汇报进度与状态（静默吞吐异常，不阻断主链路）。
     */
    private void reportTaskProgress(String videoId, String taskType, String status, Integer progress, String errorMessage) {
        try {
            contentServiceClient.taskCallback(new ContentServiceDTOs.TaskCallbackRequest(
                    videoId, taskType, status, progress, errorMessage));
        } catch (Exception e) {
            log.warn("上报流水线进度失败（忽略继续）: videoId={}, taskType={}, status={}, error={}",
                    videoId, taskType, status, e.getMessage());
        }
    }
}
