-- interaction-service: 观看进度表 interaction_watch_progress
-- 用途：
-- 1. 只承担断点续播、历史展示与"最近一次计入播放量时间"；
-- 2. 删除历史只置 deleted = 1 隐藏展示，不再物理删除，因此播放量冷却状态不会因删除/复活而重置；
-- 3. active_session_id 指向当前活跃会话，新会话超时切换时更新。

CREATE TABLE IF NOT EXISTS `interaction_watch_progress` (
    `id` CHAR(32) NOT NULL COMMENT '记录主键 UUID',
    `user_id` CHAR(32) NOT NULL COMMENT '用户账号ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `active_session_id` CHAR(32) NULL COMMENT '当前活跃观看会话 ID，无活跃会话时为空',
    `last_position` INT NOT NULL DEFAULT 0 COMMENT '上次播放头断点位置 (秒)，用于断点续播',
    `watched_duration` INT NOT NULL DEFAULT 0 COMMENT '累计有效观看总时长 (秒)',
    `first_watch_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次观看时间',
    `last_watch_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近一次心跳活跃时间',
    `last_view_claimed_at` DATETIME(3) NULL COMMENT '最近一次成功计入播放量的时间，用于重复播放冷却判断',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=仅隐藏历史展示（不释放防重状态）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_watch_progress_user_vid` (`user_id`, `vid`),
    KEY `idx_watch_progress_user_recent` (`user_id`, `last_watch_at` DESC),
    CONSTRAINT `ck_watch_progress_deleted` CHECK (`deleted` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户视频观看进度与断点表';
