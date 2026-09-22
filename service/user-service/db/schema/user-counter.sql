-- user-service: 用户计数快照表，解耦高频并发关注产生的行锁争抢；
-- 计数字段由独立原子自增/自减更新，与 user_profile 基础资料及 revision 乐观锁隔离。
CREATE TABLE IF NOT EXISTS `user_counter` (
    `account_id` CHAR(32) NOT NULL COMMENT '用户ID (与 user_profile.account_id 一致)',
    `following_count` BIGINT NOT NULL DEFAULT 0 COMMENT '关注数',
    `follower_count` BIGINT NOT NULL DEFAULT 0 COMMENT '粉丝数',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
    PRIMARY KEY (`account_id`),
    CONSTRAINT `ck_user_counter_following` CHECK (`following_count` >= 0),
    CONSTRAINT `ck_user_counter_follower` CHECK (`follower_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户互动与关系计数表';
