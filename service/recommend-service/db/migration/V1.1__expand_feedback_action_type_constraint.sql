-- -----------------------------------------------------------------------------
-- recommend-service 增量数据迁移脚本 V1.1
-- 目标：扩容 recommend_feedback_log.action_type 字段并安全更新 CHECK 约束
-- 说明：
--   1. 支持对已有生产/测试库无缝升级，将字段扩容至 VARCHAR(32) 并覆盖新增互动行为；
--   2. 通过条件检测存储过程实现幂等执行，重复执行不报错、不损坏数据。
-- -----------------------------------------------------------------------------

-- 步骤 1：扩容 action_type 字段长度 (MODIFY COLUMN 原生支持幂等)
ALTER TABLE `recommend_feedback_log`
    MODIFY COLUMN `action_type` VARCHAR(32) NOT NULL COMMENT '行为类型: IMPRESSION, PLAY, SKIP, DISLIKE, LIKE, UNLIKE, STAR, UNSTAR, SHARE, WATCH_VIEW_QUALIFIED, WATCH_COMPLETED';

-- 步骤 2：定义并调用临时存储过程安全替换 CHECK 约束
DROP PROCEDURE IF EXISTS `upgrade_ck_rfl_action_type`;

DELIMITER $$
CREATE PROCEDURE `upgrade_ck_rfl_action_type`()
BEGIN
    -- 若已存在旧约束，先移除以避免约束重名或冲突
    IF EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE()
          AND TABLE_NAME = 'recommend_feedback_log'
          AND CONSTRAINT_NAME = 'ck_rfl_action_type'
    ) THEN
        ALTER TABLE `recommend_feedback_log` DROP CHECK `ck_rfl_action_type`;
    END IF;

    -- 重新添加全量行为类型约束 (涵盖客户端直接反馈与服务端核验互动行为)
    ALTER TABLE `recommend_feedback_log`
        ADD CONSTRAINT `ck_rfl_action_type` CHECK (`action_type` IN (
            'IMPRESSION', 'PLAY', 'SKIP', 'DISLIKE',
            'LIKE', 'UNLIKE', 'STAR', 'UNSTAR', 'SHARE',
            'WATCH_VIEW_QUALIFIED', 'WATCH_COMPLETED'
        ));
END $$
DELIMITER ;

CALL `upgrade_ck_rfl_action_type`();
DROP PROCEDURE IF EXISTS `upgrade_ck_rfl_action_type`;
