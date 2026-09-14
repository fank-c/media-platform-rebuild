-- content-service: 视频与标签关联表
CREATE TABLE IF NOT EXISTS `video_tag_rel` (
    `id` CHAR(32) NOT NULL COMMENT '关联主键ID (UUID)',
    `video_id` CHAR(32) NOT NULL COMMENT '视频内部全局唯一ID (关联 video_content.id)',
    `tag_id` CHAR(32) NOT NULL COMMENT '标签主键ID (关联 content_tag.id)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_video_tag` (`video_id`, `tag_id`),
    KEY `idx_tag_video` (`tag_id`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频与标签关联多对多表';
