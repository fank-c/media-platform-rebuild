package com.calles.platform.transcode.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 视频转码调度任务工单领域聚合根 (TranscodeTask)。
 *
 * <p>核心职责：
 * <ul>
 *   <li>跟踪单项规格（如 720P MP4）从事件消费至最终回调闭环的全生命周期；</li>
 *   <li>内置状态机跃迁前置校验，防止非法并发状态跳跃；</li>
 *   <li>记录转码纯计算耗时与端到端链路总耗时，支撑可观测性监控指标。</li>
 * </ul>
 * </p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscodeTask {

    /** 任务全局唯一 ID (32位 UUID 无横杠)。 */
    private String id;

    /** 关联的主视频业务内部主键 ID (video_content.id)。 */
    private String videoId;

    /** 创作者账号 ID (auth_account.id)。 */
    private String authorId;

    /** 待转码的原始文件资产 ID (file_asset.id)。 */
    private String sourceFileId;

    /** 目标分辨率清晰度规格预设。 */
    private QualityPreset targetQuality;

    /** 目标流媒体封装格式。 */
    private MediaFormat targetFormat;

    /** 目标视频压缩编码。 */
    private MediaCodec targetCodec;

    /** 任务当前流转状态。 */
    private TranscodeTaskStatus status;

    /** 已重试次数。 */
    private int retryCount;

    /** 最大重试上限。 */
    private int maxRetries;

    /** 转码产物上传后在 file-service 注册的文件资产 ID。 */
    private String outputFileId;

    /** 产物文件字节大小。 */
    private Long outputFileSize;

    /** 实际输出视频码率 (kbps)。 */
    private Integer outputBitrate;

    /** 实际输出视频帧率 (fps)。 */
    private Integer outputFps;

    /** 实际像素宽度。 */
    private Integer outputWidth;

    /** 实际像素高度。 */
    private Integer outputHeight;

    /** 探测得出的视频实际时长 (秒)。 */
    private Integer videoDuration;

    /** 错误与失败信息摘要。 */
    private String errorMessage;

    /** FFmpeg 纯转码计算耗时 (毫秒)。 */
    private Long transcodeCostMs;

    /** 任务处理全流程端到端总耗时 (毫秒)。 */
    private Long totalCostMs;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：创建一个处于 PENDING 初始排队状态的转码任务工单。
     */
    public static TranscodeTask create(
            String videoId,
            String authorId,
            String sourceFileId,
            QualityPreset targetQuality,
            MediaFormat targetFormat,
            MediaCodec targetCodec
    ) {
        LocalDateTime now = LocalDateTime.now();
        return TranscodeTask.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .videoId(videoId)
                .authorId(authorId)
                .sourceFileId(sourceFileId)
                .targetQuality(targetQuality)
                .targetFormat(targetFormat != null ? targetFormat : MediaFormat.MP4)
                .targetCodec(targetCodec != null ? targetCodec : MediaCodec.H264)
                .status(TranscodeTaskStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 标记开始拉取原始媒体文件到本地。
     */
    public void markDownloading() {
        validateNotTerminal();
        this.status = TranscodeTaskStatus.DOWNLOADING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记原始文件就绪，正式启动本地转码压制。
     */
    public void markTranscoding() {
        validateNotTerminal();
        this.status = TranscodeTaskStatus.TRANSCODING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记本地转码压制成功，进入产物上传阶段。
     *
     * @param transcodeCostMs 纯转码耗时（毫秒）
     */
    public void markUploading(long transcodeCostMs) {
        validateNotTerminal();
        this.transcodeCostMs = transcodeCostMs;
        this.status = TranscodeTaskStatus.UPLOADING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记产物已成功上传至文件服务，进入下游内容服务门禁回调阶段。
     *
     * @param outputFileId 文件服务分配的产物 ID
     * @param outputFileSize 产物实际字节数
     */
    public void markNotifying(String outputFileId, long outputFileSize) {
        validateNotTerminal();
        this.outputFileId = outputFileId;
        this.outputFileSize = outputFileSize;
        this.status = TranscodeTaskStatus.NOTIFYING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 终局成功跃迁：全流程执行完毕，记录多媒体切片指标。
     */
    public void complete(
            String outputFileId,
            long fileSize,
            int bitrate,
            int fps,
            int width,
            int height,
            int duration,
            long totalCostMs
    ) {
        this.outputFileId = outputFileId;
        this.outputFileSize = fileSize;
        this.outputBitrate = bitrate;
        this.outputFps = fps;
        this.outputWidth = width;
        this.outputHeight = height;
        this.videoDuration = duration;
        this.totalCostMs = totalCostMs;
        this.status = TranscodeTaskStatus.COMPLETED;
        this.errorMessage = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 终局失败跃迁：记录错误原因并自增重试计数。
     */
    public void fail(String errorMessage, long totalCostMs) {
        this.status = TranscodeTaskStatus.FAILED;
        this.errorMessage = errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 997) + "..."
                : errorMessage;
        this.retryCount++;
        this.totalCostMs = totalCostMs;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 重置任务为排队状态（供重试调度器自愈使用）。
     */
    public void resetForRetry() {
        if (this.retryCount >= this.maxRetries) {
            throw new IllegalStateException("转码任务已达到最大重试上限 (" + maxRetries + "), 不可再次重试");
        }
        this.status = TranscodeTaskStatus.PENDING;
        this.updatedAt = LocalDateTime.now();
    }

    private void validateNotTerminal() {
        if (this.status.isTerminal()) {
            throw new IllegalStateException("任务已处于终局状态 [" + status + "], 不允许再次跃迁");
        }
    }
}
