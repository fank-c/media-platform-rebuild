package com.calles.platform.content.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.content.domain.model.stream.StreamCodec;
import com.calles.platform.content.domain.model.stream.StreamFormat;
import com.calles.platform.content.domain.model.stream.StreamQuality;
import com.calles.platform.content.domain.model.stream.TranscodeStatus;
import com.calles.platform.content.domain.model.stream.VideoStream;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 视频转码流数据库持久化实体 (PO)。
 *
 * <p>映射底层数据库表 {@code video_stream}，存储单条视频的多清晰度、多编码流媒体切片及转码任务状态。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("video_stream")
public class VideoStreamPO {

    /** 转码流记录主键 ID (UUID 32位无短横线)。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 所属视频的主键 ID (关联 video_content.id)。 */
    @TableField("video_id")
    private String videoId;

    /** 画质清晰度 (360P, 480P, 720P, 1080P, 1080P_60, 4K, RAW)。 */
    @TableField("quality")
    private String quality;

    /** 流媒体封装格式 (MP4, HLS, DASH)。 */
    @TableField("format")
    private String format;

    /** 视频编码标准 (H264, H265, AV1)。 */
    @TableField("codec")
    private String codec;

    /** 关联的实际对象存储文件 ID (引用 file_asset.id)。 */
    @TableField("file_id")
    private String fileId;

    /** 流文件大小 (字节)。 */
    @TableField("file_size")
    private Long fileSize;

    /** 视频码率 (kbps)。 */
    @TableField("bitrate")
    private Integer bitrate;

    /** 视频帧率 (fps)。 */
    @TableField("fps")
    private Integer fps;

    /** 异步转码状态 (PENDING, PROCESSING, COMPLETED, FAILED)。 */
    @TableField("transcode_status")
    private String transcodeStatus;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 将转码流持久化对象映射为领域实体 (VideoStream)。
     *
     * @return 对应的流媒体领域实体
     */
    public VideoStream toDomain() {
        return VideoStream.builder()
                .id(this.id)
                .videoId(this.videoId)
                .quality(this.quality != null ? StreamQuality.fromValue(this.quality) : StreamQuality.RAW)
                .format(this.format != null ? StreamFormat.valueOf(this.format) : StreamFormat.MP4)
                .codec(this.codec != null ? StreamCodec.valueOf(this.codec) : StreamCodec.H264)
                .fileId(this.fileId)
                .fileSize(this.fileSize != null ? this.fileSize : 0L)
                .bitrate(this.bitrate)
                .fps(this.fps)
                .transcodeStatus(this.transcodeStatus != null ? TranscodeStatus.valueOf(this.transcodeStatus) : TranscodeStatus.COMPLETED)
                .createdAt(this.createdAt)
                .build();
    }

    /**
     * 从转码流领域实体构造持久化实体 (PO)。
     *
     * @param domain 视频流领域实体
     * @return 对应的数据库持久化对象；若入参为 null 则返回 null
     */
    public static VideoStreamPO fromDomain(VideoStream domain) {
        if (domain == null) {
            return null;
        }
        return VideoStreamPO.builder()
                .id(domain.getId())
                .videoId(domain.getVideoId())
                .quality(domain.getQuality() != null ? domain.getQuality().getValue() : StreamQuality.RAW.getValue())
                .format(domain.getFormat() != null ? domain.getFormat().getValue() : StreamFormat.MP4.getValue())
                .codec(domain.getCodec() != null ? domain.getCodec().getValue() : StreamCodec.H264.getValue())
                .fileId(domain.getFileId())
                .fileSize(domain.getFileSize())
                .bitrate(domain.getBitrate())
                .fps(domain.getFps())
                .transcodeStatus(domain.getTranscodeStatus() != null ? domain.getTranscodeStatus().getValue() : TranscodeStatus.COMPLETED.getValue())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
