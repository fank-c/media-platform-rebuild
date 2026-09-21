-- 推荐服务用户模型表结构定义，仅由 recommend-service 持有与读写；
-- 包含用户画像状态快照表、明确屏蔽约束表以及客观行为流水事实表。

-- 1. 用户推荐兴趣画像与状态快照表
CREATE TABLE IF NOT EXISTS `recommend_user_profile` (
    `user_id` CHAR(32) NOT NULL COMMENT '用户账号全局主键 ID',
    `user_vector` JSON NULL COMMENT '当前用户即时检索向量浮点数组 JSON',
    `dimension` INT NOT NULL DEFAULT 512 COMMENT '特征向量维度',
    `vector_updated_at` DATETIME(3) NULL COMMENT '用户向量最近更新时间',
    `topic_preferences` JSON NULL COMMENT '细粒度主题偏好分快照 JSON ({"tagId": score, ...})',
    `domain_states` JSON NULL COMMENT '粗领域状态快照 JSON ({"domainId": {"exposure_count": 3, "last_active_at": ...}})',
    `recent_watch_vids` JSON NULL COMMENT '近期观看视频短码列表 JSON (["vid1", "vid2", ...])',
    `profile_version` BIGINT NOT NULL DEFAULT 1 COMMENT '画像乐观锁版本号',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户推荐兴趣画像与状态快照表';

-- 2. 用户明确屏蔽与负反馈约束表 (最高优先级硬过滤门禁)
CREATE TABLE IF NOT EXISTS `recommend_user_block` (
    `id` CHAR(32) NOT NULL COMMENT '主键 UUID 32位',
    `user_id` CHAR(32) NOT NULL COMMENT '用户账号ID',
    `block_type` VARCHAR(16) NOT NULL COMMENT '屏蔽类型: VIDEO(视频), AUTHOR(作者), TOPIC(主题标签)',
    `target_id` VARCHAR(64) NOT NULL COMMENT '屏蔽目标标识 (具体 vid / author_id / tag_id)',
    `reason` VARCHAR(64) NULL COMMENT '屏蔽原因说明 (如 NOT_INTERESTED, OFFENSIVE)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rub_user_block_target` (`user_id`, `block_type`, `target_id`),
    KEY `idx_rub_user_type` (`user_id`, `block_type`),
    CONSTRAINT `ck_rub_block_type` CHECK (`block_type` IN ('VIDEO', 'AUTHOR', 'TOPIC'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户明确屏蔽与负反馈约束表';

-- 3. 推荐模块原始行为反馈事实流水表 (不可篡改的行为日志账本)
CREATE TABLE IF NOT EXISTS `recommend_feedback_log` (
    `id` CHAR(32) NOT NULL COMMENT '主键 UUID 32位',
    `user_id` CHAR(32) NOT NULL COMMENT '用户账号ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频业务公开短码',
    `action_type` VARCHAR(24) NOT NULL COMMENT '行为类型: IMPRESSION(有效曝光), PLAY(播放消费), SKIP(滑过跳过), DISLIKE(主动负反馈)',
    `play_duration` INT NOT NULL DEFAULT 0 COMMENT '实际有效播放时长(秒)',
    `video_duration` INT NOT NULL DEFAULT 0 COMMENT '视频总时长(秒)',
    `domain_tag_ids` VARCHAR(255) NULL COMMENT '发生行为时视频领域标签ID快照 (逗号分隔)',
    `topic_tag_ids` VARCHAR(512) NULL COMMENT '发生行为时视频主题标签ID快照 (逗号分隔)',
    `author_id` CHAR(32) NULL COMMENT '发生行为时视频作者ID快照',
    `trace_id` VARCHAR(64) NULL COMMENT '全链路追踪ID',
    `occurred_at` DATETIME(3) NOT NULL COMMENT '客户端行为发生时间',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_rfl_user_occurred` (`user_id`, `occurred_at` DESC),
    KEY `idx_rfl_vid_action` (`vid`, `action_type`),
    CONSTRAINT `ck_rfl_action_type` CHECK (`action_type` IN ('IMPRESSION', 'PLAY', 'SKIP', 'DISLIKE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐模块原始行为反馈事实流水表';
