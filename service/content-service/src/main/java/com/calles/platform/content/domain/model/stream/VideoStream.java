package com.calles.platform.content.domain.model.stream;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 视频转码派生流实体 (VideoStream)。
 *
 * <p>表示同一视频在不同分辨率、流媒体封装格式和编码标准下的播放切片资产。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoStream {

    /** 流文件主键 ID (UUID)。 */
    private String id;

    /** 所属视频内部 ID (关联 video_content.id)。 */
    private String videoId;

    /** 画质规格 (如 1080P, 720P, 4K, RAW)。 */
    private StreamQuality quality;

    /** 流媒体封装格式 (MP4, HLS, DASH)。 */
    private StreamFormat format;

    /** 视频编码 (H264, H265, AV1)。 */
    private StreamCodec codec;

    /** 在 file-service 中对应的文件资产 ID (file_asset.id)。 */
    private String fileId;

    /** 文件大小 (字节)。 */
    private long fileSize;

    /** 码率 (kbps)。 */
    private Integer bitrate;

    /** 帧率 (fps)。 */
    private Integer fps;

    /** 转码任务处理状态 (PENDING, PROCESSING, COMPLETED, FAILED)。 */
    private TranscodeStatus transcodeStatus;

    /** 创建时间。 */
    private LocalDateTime createdAt;
}
