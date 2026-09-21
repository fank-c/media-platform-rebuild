package com.calles.platform.recommend.infrastructure.qdrant;

import com.calles.platform.recommend.config.QdrantProperties;
import com.calles.platform.recommend.infrastructure.qdrant.dto.QdrantDTOs;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Qdrant 向量数据库声明式 REST API 客户端。
 *
 * <p>职责与实现说明：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务特征库持久化通信组件；</li>
 *   <li><b>轻量通信</b>：基于 Spring 3.3 内置 {@link RestClient} 直连 Qdrant 6333 REST 端口；</li>
 *   <li><b>自愈建表</b>：写入前自动探测或建立 {@code video_vectors} 集合，配置 Cosine 相似度度量；</li>
 *   <li><b>容错韧性</b>：Qdrant 未启动或网络中断时记录日志并安全返回 false，不阻断发布门禁。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class QdrantClient {

    private final QdrantProperties properties;
    private final RestClient restClient;

    /** 已经确认存在的集合缓存集合，避免高频重复探测。 */
    private final Set<String> verifiedCollections = ConcurrentHashMap.newKeySet();

    public QdrantClient(QdrantProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = properties.getTimeoutMs() > 0 ? properties.getTimeoutMs() : 3000;
        factory.setConnectTimeout(Duration.ofMillis(timeout));
        factory.setReadTimeout(Duration.ofMillis(timeout));

        var clientBuilder = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory);

        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            clientBuilder.defaultHeader("api-key", properties.getApiKey());
        }

        this.restClient = clientBuilder.build();
    }

    /**
     * 确保指定名称的向量集合已在 Qdrant 中建立。
     *
     * @param collectionName 集合名称
     * @param dimension 向量维度
     */
    public void ensureCollectionExists(String collectionName, int dimension) {
        if (!properties.isEnabled() || verifiedCollections.contains(collectionName)) {
            return;
        }

        try {
            // 步骤 1：查询集合元数据
            restClient.get()
                    .uri("/collections/{name}", collectionName)
                    .retrieve()
                    .toBodilessEntity();
            verifiedCollections.add(collectionName);
            log.info("Qdrant 集合 [{}] 已就绪", collectionName);

        } catch (HttpClientErrorException.NotFound e) {
            // 步骤 2：集合不存在时自动发起创建
            log.info("Qdrant 集合 [{}] 不存在，开始自动创建 (dimension={}, distance=Cosine)", collectionName, dimension);
            try {
                restClient.put()
                        .uri("/collections/{name}", collectionName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(QdrantDTOs.CreateCollectionRequest.cosine(dimension))
                        .retrieve()
                        .toBodilessEntity();
                verifiedCollections.add(collectionName);
                log.info("Qdrant 集合 [{}] 自动创建成功", collectionName);
            } catch (Exception createEx) {
                log.warn("自动创建 Qdrant 集合 [{}] 失败: {}", collectionName, createEx.getMessage());
            }
        } catch (Exception e) {
            log.warn("探测 Qdrant 集合状态失败 (可能服务未启动): host={}, error={}", properties.getBaseUrl(), e.getMessage());
        }
    }

    /**
     * 向指定集合写入或更新向量点 (Upsert Point)。
     *
     * @param collectionName 集合名称
     * @param rawId 原始视频唯一标识
     * @param vector 特征向量浮点数列表
     * @param payload 附加业务元数据载荷
     * @return true 若成功持久化至 Qdrant，false 否则
     */
    public boolean upsertPoint(String collectionName, String rawId, List<Float> vector, Map<String, Object> payload) {
        if (!properties.isEnabled()) {
            log.debug("Qdrant 同步已通过配置关闭，跳过写入");
            return false;
        }

        try {
            // 步骤 1：确保护航集合存在
            ensureCollectionExists(collectionName, vector.size());

            // 步骤 2：规整 Point ID 为符合 Qdrant 规范的标准 UUID 格式
            String pointUuid = formatToStandardUuid(rawId);

            // 步骤 3：构建点数据载荷
            QdrantDTOs.PointStruct point = new QdrantDTOs.PointStruct(pointUuid, vector, payload);
            QdrantDTOs.UpsertPointsRequest request = new QdrantDTOs.UpsertPointsRequest(List.of(point));

            // 步骤 4：通过 REST API 发起 Upsert 请求
            restClient.put()
                    .uri("/collections/{name}/points?wait=true", collectionName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            log.info("成功同步视频特征向量至 Qdrant: videoId={}, pointId={}, collection={}", rawId, pointUuid, collectionName);
            return true;

        } catch (Exception e) {
            log.warn("同步视频向量至 Qdrant 失败 (进入降级容错): videoId={}, error={}", rawId, e.getMessage());
            return false;
        }
    }

    /**
     * 在指定向量集合中执行基于向量的 ANN 最近邻相似检索。
     *
     * @param collectionName 集合名称
     * @param vector 检索目标向量 (通常为用户兴趣向量)
     * @param limit 最大检索召回条数
     * @return 匹配的点结构列表 (含相似度分数与载荷)，若服务未启用或请求异常降级返回空列表
     */
    public List<QdrantDTOs.ScoredPoint> searchPoints(String collectionName, List<Float> vector, int limit) {
        if (!properties.isEnabled() || vector == null || vector.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 步骤 1：构造检索请求体 (启用 with_payload 附带业务元数据)
            int validLimit = limit > 0 ? limit : 20;
            QdrantDTOs.SearchPointsRequest request = new QdrantDTOs.SearchPointsRequest(vector, validLimit, true);

            // 步骤 2：通过 REST API 发送搜索请求
            QdrantDTOs.SearchPointsResponse response = restClient.post()
                    .uri("/collections/{name}/points/search", collectionName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(QdrantDTOs.SearchPointsResponse.class);

            if (response != null && response.result() != null) {
                log.debug("Qdrant 向量检索成功: collection={}, count={}", collectionName, response.result().size());
                return response.result();
            }
            return Collections.emptyList();

        } catch (Exception e) {
            log.warn("Qdrant 向量检索失败 (进入降级容错): collection={}, error={}", collectionName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将 32 位无连字符十六进制字符串规整为标准 36 位带连字符 UUID。
     */
    public static String formatToStandardUuid(String rawId) {
        if (rawId == null) {
            return java.util.UUID.randomUUID().toString();
        }
        String clean = rawId.replace("-", "").trim();
        if (clean.length() == 32) {
            return clean.substring(0, 8) + "-"
                    + clean.substring(8, 12) + "-"
                    + clean.substring(12, 16) + "-"
                    + clean.substring(16, 20) + "-"
                    + clean.substring(20);
        }
        return rawId;
    }
}
