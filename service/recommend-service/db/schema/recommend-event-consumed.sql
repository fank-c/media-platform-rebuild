-- recommend-service: 互动事件消费幂等记录表
-- 记录已消费的 interaction.video-action 等事件，防止 MQ 重复投递导致的重复处理与画像偏倚。
CREATE TABLE IF NOT EXISTS `recommend_event_consumed` (
    `event_id` VARCHAR(64) NOT NULL COMMENT '事件全局唯一标识 (32位无短横线或36位标准UUID)',
    `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型标识 (如 interaction.video-action)',
    `user_id` VARCHAR(32) NOT NULL COMMENT '用户账号ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频业务公开短码',
    `action` VARCHAR(32) NOT NULL COMMENT '行为类型 (LIKE/STAR/SHARE/WATCH_VIEW_QUALIFIED/WATCH_COMPLETED等)',
    `consumed_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次消费成功时间戳',
    PRIMARY KEY (`event_id`),
    INDEX `idx_rec_user_vid_action` (`user_id`, `vid`, `action`),
    INDEX `idx_rec_consumed_at` (`consumed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐服务互动事件消费幂等表';
