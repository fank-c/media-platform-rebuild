-- audit-service: 敏感词库与合规规则字典表
CREATE TABLE IF NOT EXISTS `audit_sensitive_word` (
    `id` CHAR(32) NOT NULL COMMENT '敏感词ID (UUID)',
    `word` VARCHAR(64) NOT NULL COMMENT '敏感词条',
    `category` VARCHAR(32) NOT NULL DEFAULT 'GENERAL' COMMENT '类别: POLITICS, PORN, VIOLENCE, ABUSE, AD, GENERAL',
    `level` VARCHAR(16) NOT NULL DEFAULT 'ILLEGAL' COMMENT '拦截级别: ILLEGAL, SUSPICIOUS',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE=生效, DISABLED=停用',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sensitive_word` (`word`),
    KEY `idx_word_lookup` (`status`, `level`),
    CONSTRAINT `ck_audit_word_level` CHECK (`level` IN ('ILLEGAL', 'SUSPICIOUS')),
    CONSTRAINT `ck_audit_word_status` CHECK (`status` IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词库与合规规则字典表';
