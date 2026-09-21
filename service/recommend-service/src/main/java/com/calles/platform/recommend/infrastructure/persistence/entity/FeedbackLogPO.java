package com.calles.platform.recommend.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐服务原始行为流水持久化实体 (PO)。
 *
 * <p>映射表名：{@code recommend_feedback_log}。</p>
 */
@Data
@TableName("recommend_feedback_log")
public class FeedbackLogPO {

    /**
     * 行为反馈流水事件全局唯一主键 ID。
     *
     * <p>业务含义与约束说明：
     * <ul>
     *   <li><b>格式规范</b>：32 位无连字符标准 UUID 字符串；</li>
     *   <li><b>主键策略</b>：由应用层在记录客户端客观行为事实时显式分配生成，采用 {@link IdType#INPUT} 模式，非数据库自增；</li>
     *   <li><b>日志溯源</b>：保证分布式环境下高并发事件只追加写入的全局唯一性，支撑全链路追踪审计与模型离线重算。</li>
     * </ul>
     * </p>
     */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 用户账号ID。 */
    @TableField("user_id")
    private String userId;

    /** 视频业务公开短码。 */
    @TableField("vid")
    private String vid;

    /** 行为动作类型: IMPRESSION, PLAY, SKIP, DISLIKE。 */
    @TableField("action_type")
    private String actionType;

    /** 实际播放时长 (秒)。 */
    @TableField("play_duration")
    private Integer playDuration;

    /** 视频总时长 (秒)。 */
    @TableField("video_duration")
    private Integer videoDuration;

    /** 发生行为时视频领域标签ID快照 (逗号分隔)。 */
    @TableField("domain_tag_ids")
    private String domainTagIds;

    /** 发生行为时视频主题标签ID快照 (逗号分隔)。 */
    @TableField("topic_tag_ids")
    private String topicTagIds;

    /** 发生行为时视频作者ID快照。 */
    @TableField("author_id")
    private String authorId;

    /** 全链路追踪ID。 */
    @TableField("trace_id")
    private String traceId;

    /** 客户端行为发生时间。 */
    @TableField("occurred_at")
    private LocalDateTime occurredAt;

    /** 流水创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
