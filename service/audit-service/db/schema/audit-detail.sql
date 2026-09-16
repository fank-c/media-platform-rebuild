-- audit-service: 审核多维度判定明细与证据表
CREATE TABLE IF NOT EXISTS `audit_detail` (
    `id` CHAR(32) NOT NULL COMMENT '明细主键ID (UUID)',
    `task_id` CHAR(32) NOT NULL COMMENT '关联主任务ID (关联 audit_task.id)',
    `dimension` VARCHAR(32) NOT NULL COMMENT '审查维度: TEXT, IMAGE, VIDEO',
    `engine_type` VARCHAR(32) NOT NULL COMMENT '判审引擎: LOCAL_DFA, RULE, ALIYUN_GREEN, MANUAL',
    `level` VARCHAR(16) NOT NULL COMMENT '该项判定级别: NORMAL, SUSPICIOUS, ILLEGAL',
    `confidence` DECIMAL(5,2) NOT NULL DEFAULT 100.00 COMMENT '置信度分值 (0.00 - 100.00)',
    `hit_words` VARCHAR(500) NULL COMMENT '命中的敏感词或规则标签快照 (逗号分隔)',
    `detail_log` TEXT NULL COMMENT '引擎原始判定结果或原因记录',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_detail_task` (`task_id`, `dimension`),
    CONSTRAINT `ck_audit_detail_dimension` CHECK (`dimension` IN ('TEXT', 'IMAGE', 'VIDEO')),
    CONSTRAINT `ck_audit_detail_level` CHECK (`level` IN ('NORMAL', 'SUSPICIOUS', 'ILLEGAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核多维度判定明细与证据表';
