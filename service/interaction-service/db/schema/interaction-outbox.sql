-- interaction-service: 领域事件 Outbox 发件箱表
-- 在业务事务内原子记录待发布事件，实现与业务事实的强一致性，并通过双轨调度保障 100% 可靠投递
CREATE TABLE IF NOT EXISTS `interaction_outbox` (
    `event_id` CHAR(36) NOT NULL COMMENT '稳定事件 UUID，重试和重放必须复用',
    `aggregate_id` VARCHAR(64) NOT NULL COMMENT '关联业务聚合根 ID (如 vid 或 userId:vid)',
    `event_type` VARCHAR(128) NOT NULL COMMENT '事件类型标识 (固定为 interaction.video-action)',
    `event_version` INT NOT NULL COMMENT '契约版本号 (固定为 1)',
    `payload` JSON NOT NULL COMMENT '符合统一信封与载荷规范的事件 JSON',
    `trace_id` VARCHAR(64) NULL COMMENT '链路追踪 ID',
    `occurred_at` DATETIME(3) NOT NULL COMMENT '事件发生时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING, PROCESSING, PUBLISHED, FAILED',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
    `next_attempt_at` DATETIME(3) NOT NULL COMMENT '下次允许重试时间',
    `lease_owner` VARCHAR(64) NULL COMMENT '当前租约所有者实例标识',
    `lease_until` DATETIME(3) NULL COMMENT '当前租约截止时间',
    `claim_token` CHAR(36) NULL COMMENT '本次抢占认领令牌',
    `published_at` DATETIME(3) NULL COMMENT '成功发布时间',
    `last_error_code` VARCHAR(64) NULL COMMENT '最后一次投递失败错误分类',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`event_id`),
    KEY `idx_interaction_outbox_dispatch` (`status`, `next_attempt_at`, `lease_until`),
    KEY `idx_interaction_outbox_aggregate` (`aggregate_id`),
    CONSTRAINT `ck_interaction_outbox_status` CHECK (`status` IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='interaction-service 领域事件 Outbox 发件箱表';
