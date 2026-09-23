-- interaction-service: interaction_watch_history 增加有效播放持久化防重字段
-- 用于在数据库事务内严格判定窗口期首次有效播放，与 Outbox 落库强一致绑定
ALTER TABLE `interaction_watch_history`
    ADD COLUMN `last_valid_play_at` DATETIME(3) NULL COMMENT '上次计为有效播放并写入 Outbox 的时间戳' AFTER `last_watch_at`;
