-- interaction-service: 观看事件凭据表 interaction_watch_event_claim
-- 用途：
-- 1. 唯一键 (user_id, vid, session_id, event_type) 是播放量与完播事件的最终防重依据；
-- 2. 凭据插入成功后才允许写 Outbox 与写入播放量增量，三者必须在同一本地事务内；
-- 3. outbox_event_id 记录同事务产生的 Outbox 事件 ID，用于事后对账与重放定位；
-- 4. claimed_at 上有索引，支撑按保留期清理历史凭据（清理不影响防重，因为冷却时间戳保存在 watch_progress）。

CREATE TABLE IF NOT EXISTS `interaction_watch_event_claim` (
    `id` CHAR(32) NOT NULL COMMENT '凭据主键 UUID',
    `user_id` CHAR(32) NOT NULL COMMENT '用户账号ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `session_id` CHAR(32) NOT NULL COMMENT '所属观看会话 ID',
    `event_type` VARCHAR(32) NOT NULL COMMENT '事件类型: WATCH_VIEW_QUALIFIED / WATCH_COMPLETED',
    `outbox_event_id` CHAR(32) NULL COMMENT '同事务写入的 Outbox 事件 ID，用于对账',
    `claimed_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '抢占成功时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_watch_event_claim` (`user_id`, `vid`, `session_id`, `event_type`),
    KEY `idx_watch_event_claim_claimed_at` (`claimed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观看播放量与完播事件凭据表';
