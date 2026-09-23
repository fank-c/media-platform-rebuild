-- interaction-service: 视频分享请求幂等防重记录表
-- 记录客户端携带的幂等键，防止网络超时或重试导致计数虚高与重复发件箱事件
CREATE TABLE IF NOT EXISTS `interaction_share_record` (
    `id` CHAR(32) NOT NULL COMMENT '主键 UUID',
    `idempotency_key` VARCHAR(64) NOT NULL COMMENT '客户端提供的请求幂等键',
    `user_id` CHAR(32) NOT NULL COMMENT '操作用户账号ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_idempotency` (`idempotency_key`),
    KEY `idx_share_user_vid` (`user_id`, `vid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频分享请求幂等防重记录表';
