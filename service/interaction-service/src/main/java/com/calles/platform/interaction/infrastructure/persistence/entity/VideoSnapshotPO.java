package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.video.VideoSnapshot;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视频元数据本地快照持久化对象 (PO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_video_snapshot")
public class VideoSnapshotPO {

    /** 视频公开业务短码，主键。 */
    @TableId("vid")
    private String vid;

    /** 视频总时长 (秒)。 */
    @TableField("duration")
    private Integer duration;

    /** 内容元数据版本号。 */
    @TableField("metadata_version")
    private Integer metadataVersion;

    /** 来源事件 ID。 */
    @TableField("source_event_id")
    private String sourceEventId;

    /** 内容状态。 */
    @TableField("status")
    private String status;

    /** 快照更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 转换为领域实体。
     *
     * @return 视频元数据快照领域实体
     */
    public VideoSnapshot toDomain() {
        return VideoSnapshot.create(
                this.vid,
                this.duration != null ? this.duration : 0,
                this.metadataVersion != null ? this.metadataVersion : 1,
                this.sourceEventId,
                this.status,
                this.updatedAt
        );
    }

    /**
     * 由领域实体构造持久化对象。
     *
     * @param domain 视频元数据快照领域实体
     * @return 持久化对象，入参为空时返回 null
     */
    public static VideoSnapshotPO fromDomain(VideoSnapshot domain) {
        if (domain == null) {
            return null;
        }
        return VideoSnapshotPO.builder()
                .vid(domain.getVid())
                .duration(domain.getDuration())
                .metadataVersion(domain.getMetadataVersion())
                .sourceEventId(domain.getSourceEventId())
                .status(domain.getStatus())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
