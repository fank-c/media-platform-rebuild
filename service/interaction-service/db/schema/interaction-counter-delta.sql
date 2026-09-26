-- interaction-service: pending counter deltas; application state and delta share one transaction.
CREATE TABLE IF NOT EXISTS `interaction_counter_delta` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '汇总顺序与主键',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频业务编码',
    `counter_type` VARCHAR(16) NOT NULL COMMENT 'VIEW LIKE STAR SHARE',
    `delta` BIGINT NOT NULL COMMENT '计数增量',
    `source_type` VARCHAR(32) NOT NULL COMMENT '业务事实类型: LIKE_ACTIVE, LIKE_INACTIVE, STAR_ACTIVE, STAR_INACTIVE, WATCH_PLAY, SHARE',
    `source_id` VARCHAR(128) NOT NULL COMMENT '业务事实主键或幂等标识',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `processed_at` DATETIME(3) NULL COMMENT '汇总成功时间',
    PRIMARY KEY (`id`),
    KEY `idx_counter_delta_pending` (`processed_at`, `id`),
    UNIQUE KEY `uk_counter_delta_source` (`source_type`, `source_id`),
    CONSTRAINT `ck_counter_delta_type` CHECK (`counter_type` IN ('VIEW', 'LIKE', 'STAR', 'SHARE')),
    CONSTRAINT `ck_counter_delta_value` CHECK (
        (`counter_type` IN ('VIEW', 'SHARE') AND `delta` > 0)
        OR (`counter_type` IN ('LIKE', 'STAR') AND `delta` <> 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='互动公开计数待汇总增量';
