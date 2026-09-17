package com.calles.platform.transcode.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 转码工单持久化对象 (TranscodeTaskPO)。
 *
 * <p>映射底层数据库物理表 {@code transcode_task}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("transcode_task")
public class TranscodeTaskPO {

    @TableId("id")
    private String id;

    @TableField("video_id")
    private String videoId;

    @TableField("author_id")
    private String authorId;

    @TableField("source_file_id")
    private String sourceFileId;

    @TableField("target_quality")
    private String targetQuality;

    @TableField("target_format")
    private String targetFormat;

    @TableField("target_codec")
    private String targetCodec;

    @TableField("status")
    private String status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("max_retries")
    private Integer maxRetries;

    @TableField("output_file_id")
    private String outputFileId;

    @TableField("output_file_size")
    private Long outputFileSize;

    @TableField("output_bitrate")
    private Integer outputBitrate;

    @TableField("output_fps")
    private Integer outputFps;

    @TableField("output_width")
    private Integer outputWidth;

    @TableField("output_height")
    private Integer outputHeight;

    @TableField("video_duration")
    private Integer videoDuration;

    @TableField("error_message")
    private String errorMessage;

    @TableField("transcode_cost_ms")
    private Long transcodeCostMs;

    @TableField("total_cost_ms")
    private Long totalCostMs;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
