-- content-service: 视频转码派生流规格表
-- 预留转码支持，记录各分辨率、编码与流封装格式对应的媒体资源
CREATE TABLE IF NOT EXISTS `video_stream` (
    `id` CHAR(32) NOT NULL COMMENT '流文件主键ID (UUID)',
    `video_id` CHAR(32) NOT NULL COMMENT '所属视频内部ID (关联 video_content.id)',
    `quality` VARCHAR(16) NOT NULL COMMENT '画质规格: 360P, 480P, 720P, 1080P, 1080P_60, 4K, RAW',
    `format` VARCHAR(16) NOT NULL DEFAULT 'MP4' COMMENT '流媒体封装格式: MP4, HLS, DASH',
    `codec` VARCHAR(16) NOT NULL DEFAULT 'H264' COMMENT '视频编码: H264, H265, AV1',
    `file_id` CHAR(32) NOT NULL COMMENT '转码后文件在 file_asset 中的ID',
    `file_size` BIGINT NOT NULL DEFAULT 0 COMMENT '流文件字节大小',
    `bitrate` INT NULL COMMENT '视频码率 (kbps)',
    `fps` INT NULL COMMENT '帧率',
    `transcode_status` VARCHAR(16) NOT NULL DEFAULT 'COMPLETED' COMMENT '转码状态: PENDING, PROCESSING, COMPLETED, FAILED',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stream_spec` (`video_id`, `quality`, `format`),
    KEY `idx_stream_file` (`file_id`),
    CONSTRAINT `ck_video_stream_quality` CHECK (`quality` IN ('360P', '480P', '720P', '1080P', '1080P_60', '4K', 'RAW')),
    CONSTRAINT `ck_video_stream_format` CHECK (`format` IN ('MP4', 'HLS', 'DASH')),
    CONSTRAINT `ck_video_stream_codec` CHECK (`codec` IN ('H264', 'H265', 'AV1')),
    CONSTRAINT `ck_video_stream_transcode_status` CHECK (`transcode_status` IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频转码派生流规格表';
