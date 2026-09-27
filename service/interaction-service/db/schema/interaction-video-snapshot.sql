-- interaction-service: 视频元数据本地快照表 interaction_video_snapshot
-- 用途：
-- 1. 播放量门槛与完播判定只读取本服务本地快照，心跳链路不做跨服务同步调用；
-- 2. duration <= 0 视为无效快照，此时只允许保存断点，不产生播放量与完播事件；
-- 3. source_event_id 唯一键承担 content.video.metadata 事件的消费幂等（重复投递只落一条）。

CREATE TABLE IF NOT EXISTS `interaction_video_snapshot` (
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `duration` INT NOT NULL DEFAULT 0 COMMENT '视频总时长 (秒)，<=0 表示快照不可用',
    `metadata_version` INT NOT NULL DEFAULT 1 COMMENT '内容元数据版本号',
    `source_event_id` CHAR(32) NOT NULL COMMENT '来源 content.video.metadata 事件 ID，用于消费幂等',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT '内容状态: PUBLISHED',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '快照更新时间',
    PRIMARY KEY (`vid`),
    UNIQUE KEY `uk_video_snapshot_source_event` (`source_event_id`),
    CONSTRAINT `ck_video_snapshot_duration` CHECK (`duration` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频元数据本地快照表';
