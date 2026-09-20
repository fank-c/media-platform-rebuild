-- 推荐服务视频特征向量存储表，仅由 recommend-service 持有并读写；
-- 记录视频特征提取模型、向量维度、向量内容 JSON、Qdrant 向量库同步状态及提审流水线状态。
CREATE TABLE IF NOT EXISTS `recommend_video_vector` (
    `id` CHAR(32) NOT NULL COMMENT '主键 UUID 32位',
    `video_id` CHAR(32) NOT NULL COMMENT '视频内部全局主键 ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `model_name` VARCHAR(64) NOT NULL COMMENT '生效的向量模型标识 (如 text-embedding-3-small, local-hash-v1)',
    `dimension` INT NOT NULL COMMENT '向量特征维度 (如 1536, 128)',
    `vector_data` JSON NOT NULL COMMENT '浮点向量数组 JSON (如 [0.12, -0.05, ...])',
    `qdrant_synced` TINYINT NOT NULL DEFAULT 1 COMMENT '是否已同步至 Qdrant (1=是, 0=否)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'COMPLETED' COMMENT '状态: PROCESSING, COMPLETED, FAILED',
    `error_message` VARCHAR(512) NULL COMMENT '失败错误信息或降级说明',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_recommend_video_vector_video_id` (`video_id`),
    KEY `idx_recommend_video_vector_vid` (`vid`),
    CONSTRAINT `ck_recommend_video_vector_status` CHECK (`status` IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT `ck_recommend_video_vector_synced` CHECK (`qdrant_synced` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐服务视频特征向量存储表';
