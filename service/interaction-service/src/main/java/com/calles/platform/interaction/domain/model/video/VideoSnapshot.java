package com.calles.platform.interaction.domain.model.video;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 视频元数据本地快照领域实体。
 *
 * <p>职责：保存 interaction-service 自有的视频时长口径，作为播放量门槛与完播判定的唯一可信来源。
 * 心跳链路只读取本快照，不跨服务同步调用 content-service。</p>
 *
 * <p>约束：{@code duration <= 0} 视为无效快照，此时只允许保存断点，不产生播放量与完播事件。</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class VideoSnapshot {

    /** 内容状态字面量：已正式发布，快照可用于资格判定。 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 视频公开业务短码，同时作为快照主键。 */
    private String vid;

    /** 视频总时长 (秒)，小于等于 0 表示快照不可用。 */
    private int duration;

    /** 内容元数据版本号，用于识别同一视频的多次元数据发布。 */
    private int metadataVersion;

    /** 来源领域事件 ID，唯一键承担消费幂等。 */
    private String sourceEventId;

    /** 内容状态，仅 {@link #STATUS_PUBLISHED} 参与资格判定。 */
    private String status;

    /** 快照更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 构造视频元数据快照。
     *
     * @param vid 视频公开业务短码
     * @param duration 视频总时长 (秒)
     * @param metadataVersion 内容元数据版本号
     * @param sourceEventId 来源事件 ID
     * @param status 内容状态
     * @param updatedAt 快照更新时间
     * @return 视频元数据快照实体
     */
    public static VideoSnapshot create(String vid, int duration, int metadataVersion,
                                       String sourceEventId, String status, LocalDateTime updatedAt) {
        return VideoSnapshot.builder()
                .vid(vid)
                .duration(duration)
                .metadataVersion(metadataVersion)
                .sourceEventId(sourceEventId)
                .status(status == null || status.isBlank() ? STATUS_PUBLISHED : status)
                .updatedAt(updatedAt)
                .build();
    }

    /**
     * 判断内容是否处于已发布状态。
     *
     * <p>可计数内容 = 存在本地快照 && status == PUBLISHED；作为起播计数的准入依据。</p>
     *
     * @return true 表示内容状态为 PUBLISHED
     */
    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    /**
     * 判断快照是否可用于合格观看与完播判定。
     *
     * <p>可判观看程度 = 可计数内容 && duration > 0。</p>
     *
     * @return true 表示时长有效且内容处于已发布状态
     */
    public boolean isUsable() {
        return duration > 0 && isPublished();
    }
}
