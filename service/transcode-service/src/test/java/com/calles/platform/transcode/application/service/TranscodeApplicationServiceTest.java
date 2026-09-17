package com.calles.platform.transcode.application.service;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.transcode.config.TranscodeProperties;
import com.calles.platform.transcode.domain.engine.TranscodeEngine;
import com.calles.platform.transcode.domain.engine.TranscodeResult;
import com.calles.platform.transcode.domain.model.MediaFormat;
import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.domain.model.TranscodeTask;
import com.calles.platform.transcode.domain.model.TranscodeTaskStatus;
import com.calles.platform.transcode.domain.repository.TranscodeTaskRepository;
import com.calles.platform.transcode.application.client.ContentServiceClient;
import com.calles.platform.transcode.application.client.FileServiceClient;
import com.calles.platform.transcode.application.client.dto.ContentServiceDTOs;
import com.calles.platform.transcode.application.client.dto.FileServiceDTOs;
import com.calles.platform.transcode.infrastructure.concurrency.TranscodeRateLimiter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频转码业务编排应用服务 (TranscodeApplicationServiceTest) 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class TranscodeApplicationServiceTest {

    @Mock private TranscodeTaskRepository taskRepository;
    @Mock private TranscodeEngine transcodeEngine;
    @Mock private FileServiceClient fileServiceClient;
    @Mock private ContentServiceClient contentServiceClient;

    private TranscodeRateLimiter rateLimiter;
    private TranscodeProperties properties;
    private TranscodeApplicationService applicationService;
    private HttpServer mockHttpServer;
    private String downloadServerUrl;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws IOException {
        // 步骤 1：启动 JDK 内置轻量模拟 HTTP 服务，支撑 HttpClient 流式下载原片
        mockHttpServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockHttpServer.createContext("/download/origin.mp4", exchange -> {
            byte[] responseBytes = "fake-origin-video-stream-content".getBytes();
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        mockHttpServer.start();
        int port = mockHttpServer.getAddress().getPort();
        downloadServerUrl = "http://127.0.0.1:" + port + "/download/origin.mp4";

        // 步骤 2：装配 Properties 与限流器
        properties = new TranscodeProperties();
        properties.setWorkDir(tempDir.getAbsolutePath());
        properties.setMaxConcurrentTasks(1);
        properties.setCallbackMaxRetries(2);

        rateLimiter = new TranscodeRateLimiter(properties);
        rateLimiter.init();

        applicationService = new TranscodeApplicationService(
                taskRepository,
                transcodeEngine,
                rateLimiter,
                fileServiceClient,
                contentServiceClient,
                properties
        );
    }

    @AfterEach
    void tearDown() {
        if (mockHttpServer != null) {
            mockHttpServer.stop(0);
        }
    }

    @Test
    @DisplayName("转码全流水线测试：新建任务 -> 下载原片 -> 引擎转码 -> 切片上传 -> 回调内容服务 -> 成功闭环")
    void processTask_fullPipeline_success(@TempDir File tempDir) throws IOException {
        String videoId = "video_1001";
        String authorId = "author_2002";
        String sourceFileId = "file_source_3003";
        QualityPreset preset = QualityPreset.P720;

        // 步骤 1：Mock 仓储与 Feign 返回值
        when(taskRepository.findBySpec(videoId, preset, MediaFormat.MP4))
                .thenReturn(Optional.empty());

        when(fileServiceClient.getInternalDownloadUrl(sourceFileId))
                .thenReturn(ApiResponse.ok(new FileServiceDTOs.DownloadUrlResponse(downloadServerUrl, Instant.now().plusSeconds(600))));

        File transcodeOutputFile = new File(tempDir, "transcode_output_720p.mp4");
        try (FileOutputStream fos = new FileOutputStream(transcodeOutputFile)) {
            fos.write("mock-transcode-720p-binary".getBytes());
        }

        TranscodeResult engineResult = TranscodeResult.builder()
                .outputFile(transcodeOutputFile)
                .fileSize(transcodeOutputFile.length())
                .bitrate(2500)
                .fps(30)
                .width(1280)
                .height(720)
                .duration(65)
                .transcodeCostMs(120L)
                .build();

        when(transcodeEngine.transcode(any(File.class), eq(preset), any(File.class)))
                .thenReturn(engineResult);

        when(fileServiceClient.uploadInternal(any(), eq(authorId), eq("MINIO")))
                .thenReturn(ApiResponse.ok(new FileServiceDTOs.FileUploadResponse(
                        "file_slice_720p_999", "720p.mp4", "video/mp4",
                        transcodeOutputFile.length(), transcodeOutputFile.length(),
                        "mocksha256", "ACTIVE", "COMPLETED"
                )));

        when(contentServiceClient.transcodeCallback(any(ContentServiceDTOs.TranscodeCallbackRequest.class)))
                .thenReturn(ApiResponse.ok());

        // 步骤 2：执行转码全流水线
        TranscodeTask resultTask = applicationService.processTask(videoId, authorId, sourceFileId, preset);

        // 步骤 3：验证状态机终态与外部交互
        assertNotNull(resultTask);
        assertEquals(TranscodeTaskStatus.COMPLETED, resultTask.getStatus());
        assertEquals("file_slice_720p_999", resultTask.getOutputFileId());
        assertEquals(65, resultTask.getVideoDuration());
        assertEquals(1280, resultTask.getOutputWidth());
        assertEquals(720, resultTask.getOutputHeight());

        verify(taskRepository).insert(any(TranscodeTask.class));
        verify(fileServiceClient).getInternalDownloadUrl(sourceFileId);
        verify(transcodeEngine).transcode(any(), eq(preset), any());
        verify(fileServiceClient).uploadInternal(any(), eq(authorId), eq("MINIO"));
        verify(contentServiceClient).transcodeCallback(any(ContentServiceDTOs.TranscodeCallbackRequest.class));
    }

    @Test
    @DisplayName("幂等测试：若任务已处于 COMPLETED 终态，直接复用返回并不触发重复转码")
    void processTask_alreadyCompleted_idempotent() {
        String videoId = "video_idempotent";
        QualityPreset preset = QualityPreset.P720;

        TranscodeTask completedTask = TranscodeTask.create(
                videoId, "author_1", "src_1", preset, MediaFormat.MP4, null
        );
        completedTask.complete("file_existing", 1024L, 2500, 30, 1280, 720, 60, 100L);

        when(taskRepository.findBySpec(videoId, preset, MediaFormat.MP4))
                .thenReturn(Optional.of(completedTask));

        TranscodeTask task = applicationService.processTask(videoId, "author_1", "src_1", preset);

        assertEquals(TranscodeTaskStatus.COMPLETED, task.getStatus());
        assertEquals("file_existing", task.getOutputFileId());
        verify(taskRepository).findBySpec(videoId, preset, MediaFormat.MP4);
    }
}
