-- interaction-service: interaction_watch_history 增加会话隔离与再次播放资格字段
-- 用于实现：
-- 1. 单会话最多触发一次有效播放事件 (session_play_emitted)；
-- 2. 独立记录当前会话有效时长 (session_watched_duration)；
-- 3. 上一会话达到 30% 消费门槛赋予再次播放资格 (eligible_for_next_play)。

ALTER TABLE `interaction_watch_history`
    ADD COLUMN `session_watched_duration` INT NOT NULL DEFAULT 0 COMMENT '当前观看会话累计有效观看时长 (秒)' AFTER `watched_duration`,
    ADD COLUMN `session_play_emitted` TINYINT NOT NULL DEFAULT 0 COMMENT '当前会话是否已经发送播放事件: 0=否, 1=是' AFTER `session_watched_duration`,
    ADD COLUMN `eligible_for_next_play` TINYINT NOT NULL DEFAULT 0 COMMENT '上一会话达到30%门槛从而允许下一次会话触发播放事件: 0=否, 1=是' AFTER `session_play_emitted`;

-- 兼容已有历史数据：若已存在历史有效播放记录，为防止升级后同一会话立即重复发送事件，设置 session_play_emitted = 1, eligible_for_next_play = 0
UPDATE `interaction_watch_history`
SET `session_play_emitted` = 1,
    `eligible_for_next_play` = 0
WHERE `last_valid_play_at` IS NOT NULL;
