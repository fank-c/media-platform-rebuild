package com.calles.platform.recommend.domain.model;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;

/**
 * 视频特征向量领域聚合根。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务核心领域模型；</li>
 *   <li><b>数据所有权</b>：映射自属表 {@code recommend_video_vector}，禁止其他服务直接读写；</li>
 *   <li><b>业务生命周期</b>：伴随提审事件生成特征，跟踪向 Qdrant 向量数据库同步状态与门禁就绪指标。</li>
 * </ul>
 * </p>
 */
@Getter
public class VideoVector {

    /** 实体内部唯一主键 UUID 32位。 */
    private final String id;

    /** 关联的内容微服务全局视频唯一标识 UUID。 */
    private final String videoId;

    /** 视频面向前台客户端的公开业务短码。 */
    private final String vid;

    /** 生效的向量模型名称标识。 */
    private String modelName;

    /** 特征向量维度。 */
    private int dimension;

    /** 向量数据 JSON 字符串 (形如 [0.12, -0.05, ...])。 */
    private String vectorData;

    /** 是否已同步至 Qdrant 向量数据库。 */
    private boolean qdrantSynced;

    /** 向量提取生命周期状态。 */
    private VectorStatus status;

    /** 异常失败详细原因描述。 */
    private String errorMessage;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最近更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 全属性重构构造方法。
     *
     * @param id 主键 ID
     * @param videoId 视频全局 ID
     * @param vid 视频短码
     * @param modelName 模型名称
     * @param dimension 维度
     * @param vectorData 向量 JSON
     * @param qdrantSynced Qdrant 同步标识
     * @param status 状态
     * @param errorMessage 错误信息
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public VideoVector(String id, String videoId, String vid, String modelName, int dimension,
                       String vectorData, boolean qdrantSynced, VectorStatus status, String errorMessage,
                       LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.videoId = videoId;
        this.vid = vid;
        this.modelName = modelName;
        this.dimension = dimension;
        this.vectorData = vectorData;
        this.qdrantSynced = qdrantSynced;
        this.status = status;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 工厂方法：为提审视频初始化处理中记录。
     *
     * @param videoId 视频全局唯一 ID
     * @param vid 视频公开短码
     * @return 新建的领域聚合根实例
     */
    public static VideoVector init(String videoId, String vid) {
        LocalDateTime now = LocalDateTime.now();
        return new VideoVector(
                UUID.randomUUID().toString().replace("-", ""),
                videoId,
                vid,
                "pending",
                0,
                "[]",
                false,
                VectorStatus.PROCESSING,
                null,
                now,
                now
        );
    }

    /**
     * 标记特征提取与同步成功完成。
     *
     * @param modelName 生效模型名称
     * @param dimension 向量维度
     * @param vectorData 向量 JSON 内容
     * @param qdrantSynced 是否已完成 Qdrant 写入
     */
    public void markCompleted(String modelName, int dimension, String vectorData, boolean qdrantSynced) {
        this.modelName = modelName;
        this.dimension = dimension;
        this.vectorData = vectorData;
        this.qdrantSynced = qdrantSynced;
        this.status = VectorStatus.COMPLETED;
        this.errorMessage = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记向量特征计算失败。
     *
     * @param errorMessage 失败错误描述
     */
    public void markFailed(String errorMessage) {
        this.status = VectorStatus.FAILED;
        this.errorMessage = errorMessage != null && errorMessage.length() > 500
                ? errorMessage.substring(0, 500)
                : errorMessage;
        this.updatedAt = LocalDateTime.now();
    }
}
