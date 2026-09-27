-- =====================================================================
-- interaction-service: 观看会话播放量与行为事件解耦结构升级补丁
-- 为 interaction_watch_session 增加 view_counted_at 与 start_request_key 字段和唯一索引
-- 支持幂等、可重复执行
-- =====================================================================

SET @col_view_counted = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'interaction_watch_session'
      AND COLUMN_NAME = 'view_counted_at'
);

SET @sql_view_counted = IF(
    @col_view_counted = 0,
    'ALTER TABLE `interaction_watch_session` ADD COLUMN `view_counted_at` DATETIME(3) NULL COMMENT ''播放量入账时间，非空表示该会话已记入播放量'' AFTER `qualified`;',
    'SELECT 1;'
);
PREPARE stmt_view_counted FROM @sql_view_counted;
EXECUTE stmt_view_counted;
DEALLOCATE PREPARE stmt_view_counted;

SET @col_start_key = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'interaction_watch_session'
      AND COLUMN_NAME = 'start_request_key'
);

SET @sql_start_key = IF(
    @col_start_key = 0,
    'ALTER TABLE `interaction_watch_session` ADD COLUMN `start_request_key` VARCHAR(64) NULL COMMENT ''起播请求幂等键'' AFTER `view_counted_at`;',
    'SELECT 1;'
);
PREPARE stmt_start_key FROM @sql_start_key;
EXECUTE stmt_start_key;
DEALLOCATE PREPARE stmt_start_key;

SET @idx_start_key = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'interaction_watch_session'
      AND INDEX_NAME = 'uk_watch_session_start_key'
);

SET @sql_idx = IF(
    @idx_start_key = 0,
    'ALTER TABLE `interaction_watch_session` ADD UNIQUE KEY `uk_watch_session_start_key` (`user_id`, `vid`, `start_request_key`);',
    'SELECT 1;'
);
PREPARE stmt_idx FROM @sql_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;
