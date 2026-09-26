-- interaction-service: 状态版本号与增量来源唯一键增量补丁（可重复执行）

-- 1. interaction_like 表追加 version 字段
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'interaction_like'
      AND COLUMN_NAME = 'version'
);

SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `interaction_like` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 1 COMMENT ''状态版本号，每次状态反转递增'' AFTER `status`',
    'SELECT "interaction_like.version already exists"'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. interaction_star_item 表追加 version 字段
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'interaction_star_item'
      AND COLUMN_NAME = 'version'
);

SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `interaction_star_item` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 1 COMMENT ''明细版本号，每次自愈复活递增'' AFTER `user_id`',
    'SELECT "interaction_star_item.version already exists"'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. interaction_counter_delta 表升级唯一索引 uk_counter_delta_source
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'interaction_counter_delta'
      AND INDEX_NAME = 'idx_counter_delta_source'
);

SET @sql = IF(@idx_exists > 0,
    'ALTER TABLE `interaction_counter_delta` DROP INDEX `idx_counter_delta_source`',
    'SELECT "idx_counter_delta_source not present"'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @uk_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'interaction_counter_delta'
      AND INDEX_NAME = 'uk_counter_delta_source'
);

SET @sql = IF(@uk_exists = 0,
    'ALTER TABLE `interaction_counter_delta` ADD UNIQUE KEY `uk_counter_delta_source` (`source_type`, `source_id`)',
    'SELECT "uk_counter_delta_source already exists"'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
