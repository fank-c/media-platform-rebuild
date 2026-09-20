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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频特征向量化全流程应用编排服务 (VideoVectorApplicationService)。
 *
 * <p>职责与执行流：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务应用层，编排特征计算、向量库写入与门禁闭环；</li>
 *   <li><b>显式策略降级</b>：委托 {@link VectorEmbeddingEngineRouter} 派发引擎，显式编排本地保底链路；</li>
 *   <li><b>幂等保护</b>：同一视频多次提审或重复 MQ 消息防重保护；</li>
 *   <li><b>双存储协同</b>：写入专业向量库 Qdrant（负责近邻索引召回），同时落库自属 MySQL 账本（负责状态审计与元数据持久化）；</li>
 *   <li><b>门禁闭环</b>：无论计算成功或失败，确定性回调内容微服务汇报 {@code VECTOR_EMBEDDING} 状态。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoVectorApplicationService {

    private final VectorEmbeddingEngineRouter embeddingRouter;
    private final RecommendEmbeddingProperties embeddingProperties;
    private final VideoVectorRepository videoVectorRepository;
    private final QdrantClient qdrantClient;
    private final QdrantProperties qdrantProperties;
    private final ContentServiceClient contentServiceClient;
    private final ObjectMapper objectMapper;

    /**
     * 处理视频提审特征向量化流水线全流程。
     *
     * @param videoId 视频全局内部主键 ID (UUID)
     * @param vid 视频公开业务短码
     * @param authorId 创作者全局 ID
     * @param title 视频标题
     * @param description 视频详细描述
     */
    public void processVideoEmbedding(String videoId, String vid, String authorId, String title, String description) {
        log.info("开始执行视频特征向量化流水线: videoId={}, vid={}", videoId, vid);

        // 步骤 1：幂等性守卫校验
        Optional<VideoVector> existingOpt = videoVectorRepository.findByVideoId(videoId);
        VideoVector videoVector;
        if (existingOpt.isPresent()) {
            videoVector = existingOpt.get();
            if (videoVector.getStatus() == VectorStatus.COMPLETED) {
                log.info("视频特征向量已存在且为 COMPLETED，执行幂等回调兜底: videoId={}", videoId);
                notifyContentServiceSafe(videoId, true, null);
                return;
            }
        } else {
            videoVector = VideoVector.init(videoId, vid);
            videoVectorRepository.save(videoVector);
        }

        try {
            // 步骤 2：多模态/文本输入元数据拼接规整
            String rawText = buildEmbeddingInput(title, description);

            // 步骤 3：应用层显式编排向量提取策略与本地平滑降级保底
            EmbeddingResult embeddingResult = generateEmbeddingWithFallback(rawText);
            if (!embeddingResult.isValid()) {
                throw new IllegalStateException("向量计算产物不合法: " + embeddingResult);
            }

            // 步骤 4：序列化向量 JSON
            String vectorJson = objectMapper.writeValueAsString(embeddingResult.vector());

            // 步骤 5：同步持久化至 Qdrant 向量数据库
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("videoId", videoId);
            payload.put("vid", vid);
            payload.put("authorId", authorId);
            payload.put("title", title != null ? title : "");
            payload.put("modelName", embeddingResult.modelName());

            boolean qdrantSynced = qdrantClient.upsertPoint(
                    qdrantProperties.getCollectionName(),
                    videoId,
                    embeddingResult.vector(),
                    payload
            );

            // 步骤 6：更新 MySQL 自属表状态为 COMPLETED
            videoVector.markCompleted(
                    embeddingResult.modelName(),
                    embeddingResult.dimension(),
                    vectorJson,
                    qdrantSynced
            );
            videoVectorRepository.update(videoVector);
            log.info("视频特征向量计算与持久化成功: videoId={}, model={}, dimension={}, qdrantSynced={}",
                    videoId, embeddingResult.modelName(), embeddingResult.dimension(), qdrantSynced);

            // 步骤 7：OpenFeign RPC 回调内容微服务，通知 VECTOR_EMBEDDING 门禁达成
            notifyContentServiceSafe(videoId, true, null);

        } catch (Exception e) {
            log.error("视频特征向量化处理发生异常: videoId={}, error={}", videoId, e.getMessage(), e);
            // 步骤 8：异常分支流转状态为 FAILED 并回调门禁报错
            videoVector.markFailed(e.getMessage());
            videoVectorRepository.update(videoVector);
            notifyContentServiceSafe(videoId, false, e.getMessage());
        }
    }

    /**
     * 构建用于特征抽取的规整文本。
     */
    private String buildEmbeddingInput(String title, String description) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title.trim());
        }
        if (description != null && !description.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(description.trim());
        }
        return sb.toString();
    }

    /**
     * 安全执行内容服务任务完成回调，具备异常阻断隔离能力。
     */
    private void notifyContentServiceSafe(String videoId, boolean success, String errorMessage) {
        try {
            TaskCallbackRequest request = success
                    ? TaskCallbackRequest.success(videoId)
                    : TaskCallbackRequest.failed(videoId, errorMessage);

            ApiResponse<Void> response = contentServiceClient.taskCallback(request);
            if (response != null && response.code() == 200) {
                log.info("成功回调 content-service 汇报 VECTOR_EMBEDDING 任务: videoId={}, success={}", videoId, success);
            } else {
                log.warn("回调 content-service 响应非 200 状态: videoId={}, response={}", videoId, response);
            }
        } catch (Exception e) {
            log.error("回调 content-service 发生 RPC 通信异常: videoId={}, error={}", videoId, e.getMessage());
        }
    }

    /**
     * 应用层显式编排主向量引擎与本地保底算法的降级调用链路。
     */
    private EmbeddingResult generateEmbeddingWithFallback(String rawText) {
        String configuredType = embeddingProperties.getType();
        VectorEmbeddingEngine primaryEngine = embeddingRouter.route(configuredType);

        // 场景 1：远程模式未配 Key 且允许本地降级时的前置安全短路
        if ("remote".equalsIgnoreCase(configuredType)) {
            String apiKey = embeddingProperties.getOpenai().getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                if (embeddingProperties.isFallbackToLocal()) {
                    log.info("未配置外部 OpenAI API Key，显式平滑降级至本地特征散列引擎 (local)");
                    return embeddingRouter.route("local").generateEmbedding(rawText);
                }
                throw new IllegalStateException("OpenAI API Key 未配置，且未开启 fallback-to-local 降级保底");
            }
        }

        // 场景 2：调用主引擎，发生网络/超时/5xx 异常时评估执行本地降级
        try {
            return primaryEngine.generateEmbedding(rawText);
        } catch (Exception e) {
            if (embeddingProperties.isFallbackToLocal() && !"local".equalsIgnoreCase(configuredType)) {
                log.warn("调用主向量引擎 [{}] 异常，触发显式降级保底，回退至本地引擎: error={}", configuredType, e.getMessage());
                return embeddingRouter.route("local").generateEmbedding(rawText);
            }
            throw e;
        }
    }
}
