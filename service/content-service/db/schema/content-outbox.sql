-- content-service: 领域事件 Outbox 表
-- 本地事务内记录待发布领域事件，解耦审核与推荐异步消息投递
CREATE TABLE IF NOT EXISTS `content_outbox` (
    `event_id` CHAR(36) NOT NULL COMMENT '稳定事件 UUID，重试和重放必须复用',
    `aggregate_id` CHAR(32) NOT NULL COMMENT '关联视频 ID',
    `event_type` VARCHAR(128) NOT NULL COMMENT '事件类型 (如 content.video.published)',
    `event_version` INT NOT NULL COMMENT '事件版本',
    `payload` JSON NOT NULL COMMENT '事件载荷 JSON',
    `trace_id` VARCHAR(64) NULL COMMENT '链路追踪 ID',
    `occurred_at` DATETIME(3) NOT NULL COMMENT '事件发生时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, PUBLISHED, FAILED',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
    `next_attempt_at` DATETIME(3) NOT NULL COMMENT '下次重试时间',
    `lease_owner` VARCHAR(64) NULL COMMENT '当前租约所有者',
    `lease_until` DATETIME(3) NULL COMMENT '当前租约截止时间',
    `claim_token` CHAR(36) NULL COMMENT '领取令牌',
    `published_at` DATETIME(3) NULL COMMENT '成功发布时间',
    `last_error_code` VARCHAR(64) NULL COMMENT '最后错误分类',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`event_id`),
    KEY `idx_content_outbox_dispatch` (`status`, `next_attempt_at`, `lease_until`),
    CONSTRAINT `ck_content_outbox_status` CHECK (`status` IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='content-service 领域事件 Outbox 表';
