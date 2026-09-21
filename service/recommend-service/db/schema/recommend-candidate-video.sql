-- 推荐服务候选池视频元数据表，仅由 recommend-service 独占持有与维护；
-- 负责承接正式发布上线的视频，维护其作者打散维度、领域/主题标签属性以及推荐可用状态。
CREATE TABLE IF NOT EXISTS `recommend_candidate_video` (
    `id` CHAR(32) NOT NULL COMMENT '主键 UUID 32位无短横线',
    `video_id` CHAR(32) NOT NULL COMMENT '视频内部全局主键 ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码 (Base62)',
    `author_id` CHAR(32) NOT NULL COMMENT '作者账号ID (用于重排同作者打散)',
    `domain_tag_ids` VARCHAR(255) NULL COMMENT '领域标签ID列表 (逗号分隔，用于领域打散与弱负向)',
    `topic_tag_ids` VARCHAR(512) NULL COMMENT '主题标签ID列表 (逗号分隔，用于偏好微调加分)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE(正常推荐), OFFLINE(已下线), BANNED(已封禁)',
    `published_at` DATETIME(3) NOT NULL COMMENT '正式公开发布时间',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rcv_video_id` (`video_id`),
    KEY `idx_rcv_vid` (`vid`),
    KEY `idx_rcv_author` (`author_id`),
    KEY `idx_rcv_status_published` (`status`, `published_at` DESC),
    CONSTRAINT `ck_rcv_status` CHECK (`status` IN ('ACTIVE', 'OFFLINE', 'BANNED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐候选池视频元数据表';
