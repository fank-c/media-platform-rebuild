package com.calles.platform.recommend.infrastructure.qdrant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Qdrant 向量数据库 REST API 通信数据传输对象定义。
 *
 * <p>涵盖集合创建、Point 插入 (Upsert) 及标准响应封装。</p>
 */
public final class QdrantDTOs {

    private QdrantDTOs() {}

    /**
     * 创建集合请求体。
     *
     * @param vectors 向量参数配置 (维度与距离算法)
     */
    public record CreateCollectionRequest(
            @JsonProperty("vectors") VectorParams vectors
    ) {
        public static CreateCollectionRequest cosine(int size) {
            return new CreateCollectionRequest(new VectorParams(size, "Cosine"));
        }
    }

    /**
     * 向量规格参数。
     *
     * @param size 向量维度
     * @param distance 距离算法 (Cosine, Dot, Euclid)
     */
    public record VectorParams(
            @JsonProperty("size") int size,
            @JsonProperty("distance") String distance
    ) {}

    /**
     * 批量 Upsert Points 请求体。
     *
     * @param points 点列表
     */
    public record UpsertPointsRequest(
            @JsonProperty("points") List<PointStruct> points
    ) {}

    /**
     * 单个向量点结构。
     *
     * @param id 点唯一标识 (标准 UUID 格式或 64 位整型)
     * @param vector 浮点特征向量数组
     * @param payload 业务元数据载荷字典
     */
    public record PointStruct(
            @JsonProperty("id") String id,
            @JsonProperty("vector") List<Float> vector,
            @JsonProperty("payload") Map<String, Object> payload
    ) {}

    /**
     * Qdrant 通用基础响应。
     *
     * @param status 状态字符串 (如 ok)
     * @param time 耗时秒数
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QdrantBaseResponse(
            @JsonProperty("status") String status,
            @JsonProperty("time") Double time
    ) {}
}
