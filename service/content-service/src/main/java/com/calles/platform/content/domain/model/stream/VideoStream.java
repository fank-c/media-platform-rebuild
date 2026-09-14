package com.calles.platform.content.domain.model.stream;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 视频转码派生流领域实体 (VideoStream)。
 *
 * <p>职责与设计原则：
 * <ul>
 *   <li><b>所属边界</b>：归属于内容域的媒体流媒体资产子域；</li>
 *   <li><b>聚合关系</b>：依附于 {@link com.calles.platform.content.domain.model.video.VideoContent} 聚合根，生命周期随主视频删除而级联清理；</li>
 *   <li><b>多清晰度资产管理</b>：承载单条视频在不同画质清晰度 (360P~4K)、封装格式 (MP4/HLS/DASH) 以及视频编码标准 (H264/H265/AV1) 下的实际切片元数据。</li>
 * </ul>
 * </p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoStream {

    /**
     * 流媒体资产主键 ID (UUID 32位无短横线)。
     */
    private String id;

    /**
     * 所属视频内部全局主键 ID (关联 video_content.id)。
     */
    private String videoId;

    /**
     * 画质清晰度档位 (如 360P, 720P, 1080P, 4K, RAW)。
     */
    private StreamQuality quality;

    /**
     * 流媒体封装格式容器协议 (MP4, HLS, DASH)。
     */
    private StreamFormat format;

    /**
     * 视频压缩编码标准 (H264, H265, AV1)。
     */
    private StreamCodec codec;

    /**
     * 在 file-service 中对应的底层物理文件资产 ID (逻辑关联 file_asset.id)。
     */
    private String fileId;

    /**
     * 媒体流文件物理大小 (单位：字节)。
     */
    private long fileSize;

    /**
     * 媒体流平均码率 (单位：kbps，选填)。
     */
    private Integer bitrate;

    /**
     * 视频播放帧率 (单位：fps，选填)。
     */
    private Integer fps;

    /**
     * 转码任务当前处理状态 (PENDING, PROCESSING, COMPLETED, FAILED)。
     */
    private TranscodeStatus transcodeStatus;

    /**
     * 转码流记录的创建时间。
     */
    private LocalDateTime createdAt;
}
