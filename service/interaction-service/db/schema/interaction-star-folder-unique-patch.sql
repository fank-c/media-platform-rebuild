-- =====================================================================
-- interaction-service: 收藏夹有效标题唯一键补丁 (可重复执行 / 幂等)
-- 为每个用户下处于有效未删除状态（deleted=0）的收藏夹标题建立唯一约束
-- 避免并发创建或改名产生重名收藏夹
-- =====================================================================

DROP PROCEDURE IF EXISTS `upgrade_interaction_star_folder_unique_patch`;

DELIMITER $$
CREATE PROCEDURE `upgrade_interaction_star_folder_unique_patch`()
BEGIN
    -- 1. 存在性检查：若 active_title 虚拟列不存在则添加
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'interaction_star_folder'
          AND COLUMN_NAME = 'active_title'
    ) THEN
        ALTER TABLE `interaction_star_folder`
            ADD COLUMN `active_title` VARCHAR(64) GENERATED ALWAYS AS (IF(`deleted` = 0, `title`, NULL)) VIRTUAL COMMENT '有效收藏夹标题虚拟列，用于唯一定界';
    END IF;

    -- 2. 存在性检查：若 uk_folder_user_active_title 唯一索引不存在则添加
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'interaction_star_folder'
          AND INDEX_NAME = 'uk_folder_user_active_title'
    ) THEN
        ALTER TABLE `interaction_star_folder`
            ADD UNIQUE KEY `uk_folder_user_active_title` (`user_id`, `active_title`);
    END IF;
END$$
DELIMITER ;

-- 执行补丁存储过程
CALL `upgrade_interaction_star_folder_unique_patch`();

-- 立即清理临时过程，保持库环境纯净
DROP PROCEDURE IF EXISTS `upgrade_interaction_star_folder_unique_patch`;
