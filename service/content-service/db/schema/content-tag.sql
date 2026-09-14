-- content-service: 内容标签全局字典表
CREATE TABLE IF NOT EXISTS `content_tag` (
    `id` CHAR(32) NOT NULL COMMENT '标签主键ID (UUID)',
    `name` VARCHAR(64) NOT NULL COMMENT '标签名称（唯一）',
    `reference_count` BIGINT NOT NULL DEFAULT 0 COMMENT '被视频引用次数/热度统计',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '标签状态: ACTIVE=启用, DISABLED=下线屏蔽',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_content_tag_name` (`name`),
    KEY `idx_content_tag_hot` (`status`, `reference_count` DESC),
    CONSTRAINT `ck_content_tag_status` CHECK (`status` IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签全局字典表';
