package com.calles.platform.recommend.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 推荐服务视频特征向量持久化实体 (PO)。
 *
 * <p>映射表名：{@code recommend_video_vector}。</p>
 */
@Data
@TableName("recommend_video_vector")
public class VideoVectorPO {

    /** 主键 UUID 32位。 */
    @TableId
    private String id;

    /** 视频内部全局主键 ID。 */
    @TableField("video_id")
    private String videoId;

    /** 视频公开业务短码。 */
    @TableField("vid")
    private String vid;

    /** 生效的向量模型标识。 */
    @TableField("model_name")
    private String modelName;

    /** 向量特征维度。 */
    @TableField("dimension")
    private Integer dimension;

    /** 浮点向量数组 JSON。 */
    @TableField("vector_data")
    private String vectorData;

    /** 是否已同步至 Qdrant (1=是, 0=否)。 */
    @TableField("qdrant_synced")
    private Integer qdrantSynced;

    /** 状态: PROCESSING, COMPLETED, FAILED。 */
    @TableField("status")
    private String status;

    /** 失败错误信息或降级说明。 */
    @TableField("error_message")
    private String errorMessage;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 最近更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
