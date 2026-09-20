package com.calles.platform.recommend.application.service;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.recommend.application.client.ContentServiceClient;
import com.calles.platform.recommend.application.client.dto.TaskCallbackRequest;
import com.calles.platform.recommend.config.QdrantProperties;
import com.calles.platform.recommend.config.RecommendEmbeddingProperties;
import com.calles.platform.recommend.domain.engine.VectorEmbeddingEngine;
import com.calles.platform.recommend.domain.engine.model.EmbeddingResult;
import com.calles.platform.recommend.domain.model.VectorStatus;
import com.calles.platform.recommend.domain.model.VideoVector;
import com.calles.platform.recommend.domain.repository.VideoVectorRepository;
import com.calles.platform.recommend.infrastructure.engine.VectorEmbeddingEngineRouter;
import com.calles.platform.recommend.infrastructure.qdrant.QdrantClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频向量化全流程应用服务单元测试 (方案A显式编排验证)。
 */
@ExtendWith(MockitoExtension.class)
class VideoVectorApplicationServiceTest {

    @Mock
    private VectorEmbeddingEngineRouter embeddingRouter;

    @Mock
    private VectorEmbeddingEngine remoteEngine;

    @Mock
    private VectorEmbeddingEngine localEngine;

    @Mock
    private VideoVectorRepository videoVectorRepository;

    @Mock
    private QdrantClient qdrantClient;

    @Mock
    private ContentServiceClient contentServiceClient;

    private RecommendEmbeddingProperties embeddingProperties;
    private QdrantProperties qdrantProperties;
    private ObjectMapper objectMapper;
    private VideoVectorApplicationService service;

    @BeforeEach
    void setUp() {
        embeddingProperties = new RecommendEmbeddingProperties();
        qdrantProperties = new QdrantProperties();
        objectMapper = new ObjectMapper();

        service = new VideoVectorApplicationService(
                embeddingRouter,
                embeddingProperties,
                videoVectorRepository,
                qdrantClient,
                qdrantProperties,
                contentServiceClient,
                objectMapper
        );
    }

    @Test
    @DisplayName("首次提审视频成功生成向量、持久化并回调内容服务")
    void shouldProcessVideoEmbeddingSuccessfully() {
        String videoId = "vid-1001";
        String vid = "cv12345678";
        String authorId = "author-01";
        String title = "Java 21 虚拟线程实践";
        String description = "深入分析虚拟线程原理与调度";

        embeddingProperties.setType("remote");
        embeddingProperties.getOpenai().setApiKey("test-api-key");

        when(videoVectorRepository.findByVideoId(videoId)).thenReturn(Optional.empty());
        when(embeddingRouter.route("remote")).thenReturn(remoteEngine);

        EmbeddingResult mockResult = new EmbeddingResult("text-embedding-3-small", 2, List.of(0.1f, 0.2f));
        when(remoteEngine.generateEmbedding("Java 21 虚拟线程实践 深入分析虚拟线程原理与调度"))
                .thenReturn(mockResult);

        when(qdrantClient.upsertPoint(anyString(), eq(videoId), eq(mockResult.vector()), anyMap()))
                .thenReturn(true);

        when(contentServiceClient.taskCallback(any())).thenReturn(ApiResponse.ok());

        // 执行
        service.processVideoEmbedding(videoId, vid, authorId, title, description);

        // 验证持久化调用
        verify(videoVectorRepository).save(any(VideoVector.class));
        ArgumentCaptor<VideoVector> updateCaptor = ArgumentCaptor.forClass(VideoVector.class);
        verify(videoVectorRepository).update(updateCaptor.capture());

        VideoVector updated = updateCaptor.getValue();
        assertThat(updated.getStatus()).isEqualTo(VectorStatus.COMPLETED);
        assertThat(updated.getModelName()).isEqualTo("text-embedding-3-small");
        assertThat(updated.isQdrantSynced()).isTrue();

        // 验证回调内容服务
        ArgumentCaptor<TaskCallbackRequest> callbackCaptor = ArgumentCaptor.forClass(TaskCallbackRequest.class);
        verify(contentServiceClient).taskCallback(callbackCaptor.capture());
        TaskCallbackRequest callback = callbackCaptor.getValue();
        assertThat(callback.videoId()).isEqualTo(videoId);
        assertThat(callback.taskType()).isEqualTo("VECTOR_EMBEDDING");
        assertThat(callback.status()).isEqualTo("SUCCESS");
        assertThat(callback.progress()).isEqualTo(100);
    }

    @Test
    @DisplayName("未配置 API Key 且开启 fallbackToLocal 时应用层显式平滑降级至本地引擎")
    void shouldFallbackToLocalWhenApiKeyNotConfigured() {
        String videoId = "vid-fallback-key";
        embeddingProperties.setType("remote");
        embeddingProperties.getOpenai().setApiKey(""); // 未配 Key
        embeddingProperties.setFallbackToLocal(true);

        when(videoVectorRepository.findByVideoId(videoId)).thenReturn(Optional.empty());
        when(embeddingRouter.route("remote")).thenReturn(remoteEngine);
        when(embeddingRouter.route("local")).thenReturn(localEngine);

        EmbeddingResult localResult = new EmbeddingResult("local-hash-v1", 2, List.of(0.5f, 0.5f));
        when(localEngine.generateEmbedding(anyString())).thenReturn(localResult);

        when(qdrantClient.upsertPoint(anyString(), eq(videoId), any(), anyMap())).thenReturn(true);
        when(contentServiceClient.taskCallback(any())).thenReturn(ApiResponse.ok());

        service.processVideoEmbedding(videoId, "cv123", "a1", "标题", "简介");

        verify(localEngine).generateEmbedding("标题 简介");
        ArgumentCaptor<VideoVector> captor = ArgumentCaptor.forClass(VideoVector.class);
        verify(videoVectorRepository).update(captor.capture());
        assertThat(captor.getValue().getModelName()).isEqualTo("local-hash-v1");
        assertThat(captor.getValue().getStatus()).isEqualTo(VectorStatus.COMPLETED);
    }

    @Test
    @DisplayName("调用远程引擎异常时应用层显式平滑降级至本地引擎")
    void shouldFallbackToLocalWhenRemoteEngineFails() {
        String videoId = "vid-fallback-remote-fail";
        embeddingProperties.setType("remote");
        embeddingProperties.getOpenai().setApiKey("valid-key");
        embeddingProperties.setFallbackToLocal(true);

        when(videoVectorRepository.findByVideoId(videoId)).thenReturn(Optional.empty());
        when(embeddingRouter.route("remote")).thenReturn(remoteEngine);
        when(embeddingRouter.route("local")).thenReturn(localEngine);

        when(remoteEngine.generateEmbedding(anyString())).thenThrow(new RuntimeException("OpenAI 超时 504"));

        EmbeddingResult localResult = new EmbeddingResult("local-hash-v1", 2, List.of(0.3f, 0.4f));
        when(localEngine.generateEmbedding(anyString())).thenReturn(localResult);

        when(qdrantClient.upsertPoint(anyString(), eq(videoId), any(), anyMap())).thenReturn(true);
        when(contentServiceClient.taskCallback(any())).thenReturn(ApiResponse.ok());

        service.processVideoEmbedding(videoId, "cv123", "a1", "标题", "简介");

        verify(localEngine).generateEmbedding("标题 简介");
        ArgumentCaptor<VideoVector> captor = ArgumentCaptor.forClass(VideoVector.class);
        verify(videoVectorRepository).update(captor.capture());
        assertThat(captor.getValue().getModelName()).isEqualTo("local-hash-v1");
        assertThat(captor.getValue().getStatus()).isEqualTo(VectorStatus.COMPLETED);
    }

    @Test
    @DisplayName("已存在 COMPLETED 记录时触发幂等保底回调并跳过重复计算")
    void shouldHandleIdempotentCase() {
        String videoId = "vid-already-done";
        VideoVector existing = new VideoVector(
                "vvid-1", videoId, "cv123", "local-hash-v1", 128, "[0.1]", true,
                VectorStatus.COMPLETED, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(videoVectorRepository.findByVideoId(videoId)).thenReturn(Optional.of(existing));
        when(contentServiceClient.taskCallback(any())).thenReturn(ApiResponse.ok());

        service.processVideoEmbedding(videoId, "cv123", "a1", "title", "desc");

        // 验证未路由引擎
        verify(embeddingRouter, never()).route(anyString());
        // 验证直接执行了内容服务门禁回调
        verify(contentServiceClient).taskCallback(any(TaskCallbackRequest.class));
    }

    @Test
    @DisplayName("模型生成与降级均抛出异常时流转为 FAILED 并回调内容服务失败")
    void shouldHandleFailureGracefully() {
        String videoId = "vid-fail";
        embeddingProperties.setType("local");

        when(videoVectorRepository.findByVideoId(videoId)).thenReturn(Optional.empty());
        when(embeddingRouter.route("local")).thenReturn(localEngine);
        when(localEngine.generateEmbedding(anyString()))
                .thenThrow(new RuntimeException("本地内存耗尽"));
        when(contentServiceClient.taskCallback(any())).thenReturn(ApiResponse.ok());

        service.processVideoEmbedding(videoId, "cv123", "a1", "title", "desc");

        ArgumentCaptor<VideoVector> updateCaptor = ArgumentCaptor.forClass(VideoVector.class);
        verify(videoVectorRepository).update(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo(VectorStatus.FAILED);
        assertThat(updateCaptor.getValue().getErrorMessage()).contains("本地内存耗尽");

        ArgumentCaptor<TaskCallbackRequest> callbackCaptor = ArgumentCaptor.forClass(TaskCallbackRequest.class);
        verify(contentServiceClient).taskCallback(callbackCaptor.capture());
        assertThat(callbackCaptor.getValue().status()).isEqualTo("FAILED");
        assertThat(callbackCaptor.getValue().errorMessage()).contains("本地内存耗尽");
    }

    @Test
    @DisplayName("并发插入发生唯一键冲突且前置线程已完成时安全转入幂等回调兜底")
    void shouldHandleConcurrentDuplicateKeyExceptionWhenAlreadyCompleted() {
        String videoId = "vid-concurrent-done";
        String vid = "cv999";

        // 第一次查询返回空，模拟并发开始
        // 第二次查询（冲突后重试）返回已被前置线程标记为 COMPLETED 的实体
        VideoVector completedVector = new VideoVector(
                "vvid-c1", videoId, vid, "local-hash-v1", 128, "[0.1]", true,
                VectorStatus.COMPLETED, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(videoVectorRepository.findByVideoId(videoId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(completedVector));

        // 模拟并发插入抛出 DuplicateKeyException
        doThrow(new DuplicateKeyException("Duplicate entry 'vid-concurrent-done' for key 'uk_recommend_video_vector_video_id'"))
                .when(videoVectorRepository).save(any(VideoVector.class));

        when(contentServiceClient.taskCallback(any())).thenReturn(ApiResponse.ok());

        // 执行
        service.processVideoEmbedding(videoId, vid, "author-1", "标题", "描述");

        // 验证没有重复执行向量计算，直接走了幂等回调兜底
        verify(embeddingRouter, never()).route(anyString());
        ArgumentCaptor<TaskCallbackRequest> callbackCaptor = ArgumentCaptor.forClass(TaskCallbackRequest.class);
        verify(contentServiceClient).taskCallback(callbackCaptor.capture());
        assertThat(callbackCaptor.getValue().status()).isEqualTo("SUCCESS");
        assertThat(callbackCaptor.getValue().videoId()).isEqualTo(videoId);
    }
}
