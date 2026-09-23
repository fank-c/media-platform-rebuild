-- =====================================================================
-- interaction-service: 全体实体伪删除 (Soft Delete / TableLogic) 补充补丁
-- 为互动中心所有业务实体表统一追加 deleted 字段与对应约束
-- 0=未删除，1=逻辑删除
-- =====================================================================

-- 1. 用户点赞表追加 deleted
ALTER TABLE `interaction_like`
    ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除' AFTER `status`,
    ADD CONSTRAINT `ck_interaction_like_deleted` CHECK (`deleted` IN (0, 1));

-- 2. 收藏夹表追加 deleted
ALTER TABLE `interaction_star_folder`
    ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除' AFTER `status`,
    ADD CONSTRAINT `ck_star_folder_deleted` CHECK (`deleted` IN (0, 1));

-- 3. 收藏视频明细表追加 deleted
ALTER TABLE `interaction_star_item`
    ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除' AFTER `user_id`,
    ADD CONSTRAINT `ck_star_item_deleted` CHECK (`deleted` IN (0, 1));

-- 4. 用户播放历史表追加 deleted
ALTER TABLE `interaction_watch_history`
    ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除' AFTER `last_valid_play_at`,
    ADD CONSTRAINT `ck_watch_history_deleted` CHECK (`deleted` IN (0, 1));

-- 5. 视频分享防重表追加 deleted
ALTER TABLE `interaction_share_record`
    ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除' AFTER `vid`,
    ADD CONSTRAINT `ck_share_record_deleted` CHECK (`deleted` IN (0, 1));
