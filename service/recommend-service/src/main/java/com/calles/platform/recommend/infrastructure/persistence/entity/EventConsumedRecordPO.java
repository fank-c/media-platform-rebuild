package com.calles.platform.recommend.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 互动事件消费幂等记录持久化实体 (PO)。
 *
 * <p>映射数据表：{@code recommend_event_consumed}。</p>
 */
@Data
@TableName("recommend_event_consumed")
public class EventConsumedRecordPO {

    /**
     * 事件全局唯一标识 (主键)。
     *
     * <p>采用 {@link IdType#INPUT} 显式注入模式，严格复用 MQ 传输协议中的 eventId。</p>
     */
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    /** 事件类型标识 (如 interaction.video-action)。 */
    @TableField("event_type")
    private String eventType;

    /** 行为触发用户账号 ID。 */
    @TableField("user_id")
    private String userId;

    /** 视频公开短码。 */
    @TableField("vid")
    private String vid;

    /** 目标作者账号 ID。 */
    @TableField("author_id")
    private String authorId;

    /** 行为动作类型字面量 (如 LIKE/STAR/SHARE/FOLLOW 等)。 */
    @TableField("action")
    private String action;

    /** 动作生效状态 (ACTIVE/INACTIVE)。 */
    @TableField("state")
    private String state;

    /** 消费入库时间戳。 */
    @TableField("consumed_at")
    private LocalDateTime consumedAt;
}
