-- user-service: 用户关注关系表，记录用户之间的单向关注拓扑；
-- 取消关注采用状态更新 (follow_status=0)，保障操作幂等并保留最后更新痕迹。
CREATE TABLE IF NOT EXISTS `user_follow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id` CHAR(32) NOT NULL COMMENT '关注者用户ID (发起人)',
    `follow_id` CHAR(32) NOT NULL COMMENT '被关注者用户ID (目标用户)',
    `follow_status` TINYINT NOT NULL DEFAULT 1 COMMENT '关注状态: 1=已关注, 0=已取消',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '初次关注时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后状态更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_follow` (`user_id`, `follow_id`),
    KEY `idx_user_following` (`user_id`, `follow_status`, `updated_at` DESC) COMMENT '我的关注列表高效分页',
    KEY `idx_follow_fans` (`follow_id`, `follow_status`, `updated_at` DESC) COMMENT '我的粉丝列表高效分页'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关注关系表';
