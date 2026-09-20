-- -----------------------------------------------------------------------------
-- content-service 增量数据迁移脚本 V1.1
-- 目标：为 content_tag 标签全局字典表添加 tag_type 标签类型字段
-- -----------------------------------------------------------------------------

ALTER TABLE `content_tag`
    ADD COLUMN `tag_type` VARCHAR(16) NOT NULL DEFAULT 'TOPIC' COMMENT '标签类型: DOMAIN=泛化领域, TOPIC=具体主题' AFTER `name`,
    ADD KEY `idx_content_tag_type_hot` (`tag_type`, `status`, `reference_count` DESC),
    ADD CONSTRAINT `ck_content_tag_type` CHECK (`tag_type` IN ('DOMAIN', 'TOPIC'));
