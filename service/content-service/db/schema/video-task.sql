-- content-service: 视频处理异步任务与流水线调度表
-- 跟踪每个视频在发布流水线中的子任务状态（审核、各规格转码、向量提取），支撑分级就绪快速发布与超时补偿
CREATE TABLE IF NOT EXISTS `video_task` (
    `id` CHAR(32) NOT NULL COMMENT '任务主键ID (UUID)',
    `video_id` CHAR(32) NOT NULL COMMENT '所属视频内部ID (关联 video_content.id)',
    `task_type` VARCHAR(32) NOT NULL COMMENT '任务类型: AUDIT, TRANSCODE_720P, TRANSCODE_1080P, TRANSCODE_4K, VECTOR_EMBEDDING',
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态: PENDING, RUNNING, SUCCESS, FAILED, CANCELED',
    `progress` INT NOT NULL DEFAULT 0 COMMENT '执行进度百分比 (0-100)',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retries` INT NOT NULL DEFAULT 3 COMMENT '最大重试上限',
    `error_message` VARCHAR(500) NULL COMMENT '失败错误信息',
    `started_at` DATETIME(3) NULL COMMENT '任务开始执行时间',
    `completed_at` DATETIME(3) NULL COMMENT '任务完成或终止时间',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_video_task_type` (`video_id`, `task_type`),
    KEY `idx_task_status_started` (`status`, `started_at`),
    KEY `idx_task_video` (`video_id`),
    CONSTRAINT `ck_video_task_type` CHECK (`task_type` IN ('AUDIT', 'TRANSCODE_720P', 'TRANSCODE_1080P', 'TRANSCODE_4K', 'VECTOR_EMBEDDING')),
    CONSTRAINT `ck_video_task_status` CHECK (`status` IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELED')),
    CONSTRAINT `ck_video_task_progress` CHECK (`progress` >= 0 AND `progress` <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频处理异步任务与流水线调度表';
