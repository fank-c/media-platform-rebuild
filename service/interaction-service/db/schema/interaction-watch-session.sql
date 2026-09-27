-- interaction-service: 观看会话表 interaction_watch_session
-- 用途：
-- 1. 累计"本次会话"服务端校验后的有效观看时长，是播放量与完播判定的唯一时长口径；
-- 2. duration_snapshot 与 qualification_threshold 在会话创建时固定，避免视频元数据变化导致本次会话门槛漂移；
-- 3. last_sequence 支撑重复心跳的幂等返回与乱序心跳不回退；
-- 4. closed_at 仅在超过会话超时切换新会话时写入，历史会话行保留用于审计与对账。

CREATE TABLE IF NOT EXISTS `interaction_watch_session` (
    `session_id` CHAR(32) NOT NULL COMMENT '会话主键 UUID，由服务端首次心跳生成',
    `user_id` CHAR(32) NOT NULL COMMENT '用户账号ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `duration_snapshot` INT NOT NULL DEFAULT 0 COMMENT '会话开始时使用的视频时长快照 (秒)',
    `qualification_threshold` INT NOT NULL DEFAULT 0 COMMENT '本会话播放量门槛 (秒)，创建时固定',
    `credited_duration` INT NOT NULL DEFAULT 0 COMMENT '本会话服务端校验后的累计有效观看时长 (秒)',
    `last_sequence` BIGINT NULL COMMENT '已处理的最大客户端心跳序号，为空表示客户端未提供序号',
    `last_position` INT NOT NULL DEFAULT 0 COMMENT '本会话最近一次播放位置 (秒)',
    `qualified` TINYINT NOT NULL DEFAULT 0 COMMENT '本会话是否达到播放量门槛: 0=否, 1=是',
    `view_counted_at` DATETIME(3) NULL COMMENT '播放量入账时间，非空表示该会话已记入播放量',
    `start_request_key` VARCHAR(64) NULL COMMENT '起播请求幂等键',
    `started_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '会话开始时间',
    `last_heartbeat_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近一次有效心跳时间',
    `closed_at` DATETIME(3) NULL COMMENT '会话关闭时间，超时切换新会话时写入',
    PRIMARY KEY (`session_id`),
    UNIQUE KEY `uk_watch_session_start_key` (`user_id`, `vid`, `start_request_key`),
    KEY `idx_watch_session_user_vid_heartbeat` (`user_id`, `vid`, `last_heartbeat_at` DESC),
    KEY `idx_watch_session_heartbeat` (`last_heartbeat_at`),
    CONSTRAINT `ck_watch_session_qualified` CHECK (`qualified` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户观看会话与有效时长累计表';
