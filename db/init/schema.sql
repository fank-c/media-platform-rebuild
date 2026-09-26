-- 已迁移业务表的完整 Schema 初始化脚本。请在仓库根目录通过 MySQL 客户端执行：
-- mysql --database=media_platform < db/init/schema.sql
-- 本脚本仅面向空库初始化：不创建数据库、不读取旧表，也不做数据回填。
-- 各服务在 db/schema/ 中保留同一份按表拆分的定义；修改表结构时必须同步更新两处。

-- auth-service：认证账户、凭据、角色和状态；其他服务不得直接读写本表。
-- id 由 MyBatis-Plus ASSIGN_UUID 生成 32 位 UUID 字符串，不使用数据库自增序列。
CREATE TABLE IF NOT EXISTS `auth_account` (
    `id` CHAR(32) NOT NULL,
    `email` VARCHAR(255) NOT NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `role` VARCHAR(16) NOT NULL DEFAULT 'USER',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_auth_account_email` (`email`),
    CONSTRAINT `ck_auth_account_role` CHECK (`role` IN ('USER', 'ADMIN')),
    CONSTRAINT `ck_auth_account_status` CHECK (`status` IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT `ck_auth_account_deleted` CHECK (`deleted` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='认证账户凭据、角色和状态仅由 auth-service 持有；password_hash 仅允许 BCrypt 哈希，禁止明文和 MD5。';

-- auth-service：注册事务内记录待发布事件，RabbitMQ 故障时保留并在恢复后重试。
CREATE TABLE IF NOT EXISTS `auth_outbox` (
    `event_id` CHAR(36) NOT NULL COMMENT '稳定事件 UUID，重试和重放必须复用',
    `aggregate_id` CHAR(32) NOT NULL COMMENT '认证账户 ID',
    `event_type` VARCHAR(128) NOT NULL,
    `event_version` INT NOT NULL,
    `payload` JSON NOT NULL,
    `trace_id` VARCHAR(64) NULL,
    `occurred_at` DATETIME(3) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    `attempts` INT NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME(3) NOT NULL,
    `lease_owner` VARCHAR(64) NULL,
    `lease_until` DATETIME(3) NULL,
    `claim_token` CHAR(36) NULL,
    `published_at` DATETIME(3) NULL,
    `last_error_code` VARCHAR(64) NULL,
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`event_id`),
    KEY `idx_auth_outbox_dispatch` (`status`, `next_attempt_at`, `lease_until`),
    CONSTRAINT `ck_auth_outbox_status` CHECK (`status` IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='auth-service 领域事件 Outbox';

-- auth-service：新认证库已有普通账号的资料补齐进度，避免重复运行生成不同 eventId。
CREATE TABLE IF NOT EXISTS `auth_profile_backfill_progress` (
    `account_id` CHAR(32) NOT NULL,
    `event_type` VARCHAR(128) NOT NULL,
    `event_version` INT NOT NULL,
    `event_id` CHAR(36) NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`account_id`, `event_type`, `event_version`),
    UNIQUE KEY `uk_auth_profile_backfill_event` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新认证库普通账号资料补齐进度';

-- user-service：用户资料；account_id 复用 auth_account.id 的 UUID，禁止跨服务外键、触发器和 SQL Join。
CREATE TABLE IF NOT EXISTS `user_profile` (
    `account_id` CHAR(32) NOT NULL,
    `nickname` VARCHAR(64) NULL,
    `avatar_url` VARCHAR(512) NULL,
    `bio` VARCHAR(500) NULL,
    `city` VARCHAR(100) NULL,
    `gender` TINYINT NULL,
    `birthday` DATE NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除',
    `revision` BIGINT NOT NULL DEFAULT 0 COMMENT '资料并发修改版本',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`account_id`),
    CONSTRAINT `ck_user_profile_status` CHECK (`status` IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT `ck_user_profile_deleted` CHECK (`deleted` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='用户资料仅由 user-service 持有；account_id 逻辑关联 auth_account.id，gender 语义未定义，暂不施加枚举约束。';

-- user-service：账号创建事件消费幂等记录，与资料初始化处于同一事务。
CREATE TABLE IF NOT EXISTS `user_consumed_event` (
    `consumer_name` VARCHAR(128) NOT NULL,
    `event_id` CHAR(36) NOT NULL,
    `event_type` VARCHAR(128) NOT NULL,
    `event_version` INT NOT NULL,
    `aggregate_id` CHAR(32) NOT NULL,
    `outcome` VARCHAR(32) NOT NULL,
    `processed_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`consumer_name`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user-service 消费幂等登记';

-- file-service 独占文件元数据。此快照仅供空库初始化；已有环境执行前后须对照 service/file-service/db/schema/file-asset.sql。
CREATE TABLE IF NOT EXISTS file_asset (
  id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的文件ID',
  origin_name VARCHAR(255) NOT NULL COMMENT '清理后的展示文件名，不是对象路径',
  mime VARCHAR(127) NOT NULL COMMENT '声明并规范化的MIME，不代表安全鉴定',
  declared_size BIGINT NOT NULL COMMENT '客户端声明字节数，完成前不是真实大小',
  size BIGINT NULL COMMENT '服务端实际确认字节数，未完成为空',
  storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务内最终对象key，不对外暴露',
  storage_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对象存储类型，统一为OSS',
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '完成确认后的可信小写SHA-256',
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '资源启用状态',
  upload_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT '上传确认状态',
  upload_expires_at DATETIME(3) NULL COMMENT '未完成上传确认期限，UTC',
  create_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建认证主体ID',
  update_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '最后业务操作者ID',
  create_at DATETIME(3) NOT NULL COMMENT 'UTC创建时刻',
  update_at DATETIME(3) NOT NULL COMMENT 'UTC最后变更时刻',
  delete_requested_at DATETIME(3) NULL COMMENT '删除闸门建立时间，非空且未墓碑表示删除恢复中',
  deleted_at DATETIME(3) NULL COMMENT '逻辑删除墓碑时间，NULL为未删除',
  upload_protocol VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LEGACY_V1' COMMENT '记录使用的上传协议',
  staging_storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'V2暂存对象key，旧协议为空',
  expected_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'V2初始化绑定的期望摘要，完成前不写入sha256',
  verification_requested_at DATETIME(3) NULL COMMENT 'V2首次进入VERIFYING的时间',
  verified_source_etag VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'V2 HEAD观察到的staging ETag',
  staging_cleaned_at DATETIME(3) NULL COMMENT 'V2 staging对象明确删除的时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_file_storage (storage_type, storage_key),
  UNIQUE KEY uk_file_staging_storage (storage_type, staging_storage_key),
  KEY idx_file_owner (create_by, status, create_at, id),
  KEY idx_file_expiry (upload_status, delete_requested_at, deleted_at, upload_expires_at, id),
  KEY idx_file_delete_recovery (delete_requested_at, id),
  KEY idx_file_verification_recovery (upload_status, delete_requested_at, deleted_at, verification_requested_at, id),
  KEY idx_file_staging_cleanup (upload_status, staging_cleaned_at, id),
  CONSTRAINT ck_file_size CHECK (declared_size > 0 AND
    ((upload_status = 'COMPLETED' AND size IS NOT NULL AND size = declared_size) OR
     (upload_status IN ('PENDING', 'VERIFYING', 'EXPIRED') AND size IS NULL))),
  CONSTRAINT ck_file_storage CHECK (storage_type IN ('MINIO', 'ALIYUN_OSS')),
  CONSTRAINT ck_file_upload CHECK (upload_status IN ('PENDING', 'VERIFYING', 'COMPLETED', 'EXPIRED')),
  CONSTRAINT ck_file_protocol CHECK (upload_protocol IN ('LEGACY_V1', 'SERVER_MULTIPART_V1', 'DIRECT_STAGED_CHECKSUM_V2')),
  CONSTRAINT ck_file_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT ck_file_digest CHECK (
    (upload_status = 'COMPLETED' AND sha256 IS NOT NULL AND CHAR_LENGTH(sha256) = 64) OR
    (upload_status IN ('PENDING', 'VERIFYING', 'EXPIRED') AND sha256 IS NULL)),
  CONSTRAINT ck_file_v2_fields CHECK (
    (upload_protocol = 'DIRECT_STAGED_CHECKSUM_V2'
      AND staging_storage_key IS NOT NULL
      AND expected_sha256 IS NOT NULL
      AND CHAR_LENGTH(expected_sha256) = 64
      AND expected_sha256 = LOWER(expected_sha256))
    OR (upload_protocol IN ('LEGACY_V1', 'SERVER_MULTIPART_V1') AND staging_storage_key IS NULL AND expected_sha256 IS NULL)),
  CONSTRAINT ck_file_v2_verification CHECK (
    (upload_status = 'VERIFYING' AND upload_protocol = 'DIRECT_STAGED_CHECKSUM_V2' AND verification_requested_at IS NOT NULL)
    OR upload_status <> 'VERIFYING')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='file-service 独占的文件元数据；V2直传使用staging到permanent的条件迁移';

-- content-service: 视频内容聚合根表
CREATE TABLE IF NOT EXISTS `video_content` (
    `id` CHAR(32) NOT NULL COMMENT '视频内部全局唯一ID (UUID)',
    `vid` VARCHAR(32) NOT NULL COMMENT '业务对外公开编码 (如 cv2026090001)',
    `author_id` CHAR(32) NOT NULL COMMENT '作者账号ID (逻辑关联 auth_account.id)',
    `title` VARCHAR(128) NOT NULL COMMENT '视频标题',
    `description` VARCHAR(2000) NULL COMMENT '视频简介描述',
    `video_file_id` CHAR(32) NOT NULL COMMENT '主视频文件ID (引用 file_asset.id)',
    `cover_file_id` CHAR(32) NOT NULL COMMENT '封面图片文件ID (引用 file_asset.id)',
    `duration` INT NOT NULL DEFAULT 0 COMMENT '视频时长 (秒)',
    `tags` VARCHAR(255) NULL COMMENT '轻量标签快照 (英文逗号分隔)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '平台可用状态: ACTIVE=正常, DISABLED=违规封禁/冻结',
    `publish_status` VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT '发布生命周期: DRAFT, AUDITING, PUBLISHED, REJECTED, OFFLINE',
    `reject_reason` VARCHAR(255) NULL COMMENT '审核拒绝或下架原因',
    `visibility` VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' COMMENT '可见范围: PUBLIC, PRIVATE, UNLISTED',
    `published_at` DATETIME(3) NULL COMMENT '首次公开发布时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除',
    `revision` BIGINT NOT NULL DEFAULT 0 COMMENT '并发修改版本乐观锁',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_video_vid` (`vid`),
    KEY `idx_video_author` (`author_id`, `status`, `publish_status`, `created_at`),
    KEY `idx_video_publish` (`status`, `publish_status`, `visibility`, `published_at`),
    CONSTRAINT `ck_video_content_status` CHECK (`status` IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT `ck_video_content_publish_status` CHECK (`publish_status` IN ('DRAFT', 'AUDITING', 'PUBLISHED', 'REJECTED', 'OFFLINE')),
    CONSTRAINT `ck_video_content_visibility` CHECK (`visibility` IN ('PUBLIC', 'PRIVATE', 'UNLISTED')),
    CONSTRAINT `ck_video_content_deleted` CHECK (`deleted` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频内容聚合根表';

-- content-service: 视频转码派生流规格表
CREATE TABLE IF NOT EXISTS `video_stream` (
    `id` CHAR(32) NOT NULL COMMENT '流文件主键ID (UUID)',
    `video_id` CHAR(32) NOT NULL COMMENT '所属视频内部ID (关联 video_content.id)',
    `quality` VARCHAR(16) NOT NULL COMMENT '画质规格: 360P, 480P, 720P, 1080P, 1080P_60, 4K, RAW',
    `format` VARCHAR(16) NOT NULL DEFAULT 'MP4' COMMENT '流媒体封装格式: MP4, HLS, DASH',
    `codec` VARCHAR(16) NOT NULL DEFAULT 'H264' COMMENT '视频编码: H264, H265, AV1',
    `file_id` CHAR(32) NOT NULL COMMENT '转码后文件在 file_asset 中的ID',
    `file_size` BIGINT NOT NULL DEFAULT 0 COMMENT '流文件字节大小',
    `bitrate` INT NULL COMMENT '视频码率 (kbps)',
    `fps` INT NULL COMMENT '帧率',
    `transcode_status` VARCHAR(16) NOT NULL DEFAULT 'COMPLETED' COMMENT '转码状态: PENDING, PROCESSING, COMPLETED, FAILED',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stream_spec` (`video_id`, `quality`, `format`),
    KEY `idx_stream_file` (`file_id`),
    CONSTRAINT `ck_video_stream_quality` CHECK (`quality` IN ('360P', '480P', '720P', '1080P', '1080P_60', '4K', 'RAW')),
    CONSTRAINT `ck_video_stream_format` CHECK (`format` IN ('MP4', 'HLS', 'DASH')),
    CONSTRAINT `ck_video_stream_codec` CHECK (`codec` IN ('H264', 'H265', 'AV1')),
    CONSTRAINT `ck_video_stream_transcode_status` CHECK (`transcode_status` IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频转码派生流规格表';

-- content-service: 视频处理异步任务与流水线调度表
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

-- content-service: 领域事件 Outbox 表
CREATE TABLE IF NOT EXISTS `content_outbox` (
    `event_id` CHAR(36) NOT NULL COMMENT '稳定事件 UUID，重试和重放必须复用',
    `aggregate_id` CHAR(32) NOT NULL COMMENT '关联视频 ID',
    `event_type` VARCHAR(128) NOT NULL COMMENT '事件类型 (如 content.video.published)',
    `event_version` INT NOT NULL COMMENT '事件版本',
    `payload` JSON NOT NULL COMMENT '事件载荷 JSON',
    `trace_id` VARCHAR(64) NULL COMMENT '链路追踪 ID',
    `occurred_at` DATETIME(3) NOT NULL COMMENT '事件发生时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, PUBLISHED, FAILED',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
    `next_attempt_at` DATETIME(3) NOT NULL COMMENT '下次重试时间',
    `lease_owner` VARCHAR(64) NULL COMMENT '当前租约所有者',
    `lease_until` DATETIME(3) NULL COMMENT '当前租约截止时间',
    `claim_token` CHAR(36) NULL COMMENT '领取令牌',
    `published_at` DATETIME(3) NULL COMMENT '成功发布时间',
    `last_error_code` VARCHAR(64) NULL COMMENT '最后错误分类',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`event_id`),
    KEY `idx_content_outbox_dispatch` (`status`, `next_attempt_at`, `lease_until`),
    CONSTRAINT `ck_content_outbox_status` CHECK (`status` IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='content-service 领域事件 Outbox 表';

-- content-service: 内容标签全局字典表
CREATE TABLE IF NOT EXISTS `content_tag` (
    `id` CHAR(32) NOT NULL COMMENT '标签主键ID (UUID)',
    `name` VARCHAR(64) NOT NULL COMMENT '标签名称（唯一）',
    `tag_type` VARCHAR(16) NOT NULL DEFAULT 'TOPIC' COMMENT '标签类型: DOMAIN=泛化领域, TOPIC=具体主题',
    `reference_count` BIGINT NOT NULL DEFAULT 0 COMMENT '被视频引用次数/热度统计',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '标签状态: ACTIVE=启用, DISABLED=下线屏蔽',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_content_tag_name` (`name`),
    KEY `idx_content_tag_hot` (`status`, `reference_count` DESC),
    KEY `idx_content_tag_type_hot` (`tag_type`, `status`, `reference_count` DESC),
    CONSTRAINT `ck_content_tag_status` CHECK (`status` IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT `ck_content_tag_type` CHECK (`tag_type` IN ('DOMAIN', 'TOPIC'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容标签全局字典表';

-- content-service: 视频与标签关联表
CREATE TABLE IF NOT EXISTS `video_tag_rel` (
    `id` CHAR(32) NOT NULL COMMENT '关联主键ID (UUID)',
    `video_id` CHAR(32) NOT NULL COMMENT '视频内部全局唯一ID (关联 video_content.id)',
    `tag_id` CHAR(32) NOT NULL COMMENT '标签主键ID (关联 content_tag.id)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_video_tag` (`video_id`, `tag_id`),
    KEY `idx_tag_video` (`tag_id`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频与标签关联多对多表';

-- audit-service: 内容安全审核主任务表
CREATE TABLE IF NOT EXISTS `audit_task` (
    `id` CHAR(32) NOT NULL COMMENT '任务主键ID (UUID)',
    `task_no` VARCHAR(64) NOT NULL COMMENT '业务流水号 (如 aud_20260915_xxxx)',
    `biz_type` VARCHAR(32) NOT NULL DEFAULT 'VIDEO' COMMENT '业务类型: VIDEO, COMMENT, AVATAR',
    `biz_id` CHAR(32) NOT NULL COMMENT '业务内部主键 (对应 video_content.id)',
    `biz_vid` VARCHAR(32) NULL COMMENT '业务公开短码 (对应 video_content.vid)',
    `author_id` CHAR(32) NOT NULL COMMENT '作者账号ID',
    `title_snapshot` VARCHAR(128) NOT NULL COMMENT '标题快照',
    `description_snapshot` VARCHAR(2000) NULL COMMENT '简介快照',
    `cover_file_id` CHAR(32) NOT NULL COMMENT '封面图片文件ID',
    `video_file_id` CHAR(32) NOT NULL COMMENT '视频文件ID',
    `stage` VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' COMMENT '审核阶段: RECEIVED, MACHINE_AUDITING, MANUAL_PENDING, FINISHED',
    `result` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '审核结果: PENDING, PASSED, REJECTED',
    `reject_reason` VARCHAR(255) NULL COMMENT '审核驳回原因',
    `review_level` VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '风险级别: NORMAL, SUSPICIOUS, ILLEGAL',
    `operator_id` VARCHAR(64) NOT NULL DEFAULT 'SYSTEM' COMMENT '终审操作人 (SYSTEM 或 管理员ID)',
    `callback_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '回调状态: PENDING, SUCCESS, FAILED',
    `callback_retries` INT NOT NULL DEFAULT 0 COMMENT '回调已重试次数',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_audit_task_no` (`task_no`),
    KEY `idx_audit_biz` (`biz_type`, `biz_id`, `created_at`),
    KEY `idx_audit_stage_result` (`stage`, `result`, `created_at`),
    KEY `idx_audit_callback` (`callback_status`, `updated_at`),
    CONSTRAINT `ck_audit_task_stage` CHECK (`stage` IN ('RECEIVED', 'MACHINE_AUDITING', 'MANUAL_PENDING', 'FINISHED')),
    CONSTRAINT `ck_audit_task_result` CHECK (`result` IN ('PENDING', 'PASSED', 'REJECTED')),
    CONSTRAINT `ck_audit_task_review_level` CHECK (`review_level` IN ('NORMAL', 'SUSPICIOUS', 'ILLEGAL')),
    CONSTRAINT `ck_audit_task_callback_status` CHECK (`callback_status` IN ('PENDING', 'SUCCESS', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容安全审核主任务表';

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

-- transcode-service: 视频流转码调度任务工单表
-- 独占 transcode_* 表所有权，记录各画质规格转码状态、产物文件引用与执行耗时
CREATE TABLE IF NOT EXISTS `transcode_task` (
    `id` CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '转码任务全局唯一ID (UUID无横杠)',
    `video_id` CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联的主视频ID (video_content.id)',
    `author_id` CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创作者账号ID (auth_account.id)',
    `source_file_id` CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '待转码的原始文件ID (file_asset.id)',
    `target_quality` VARCHAR(16) NOT NULL COMMENT '目标清晰度规格: 360P, 480P, 720P, 1080P, 1080P_60, 4K',
    `target_format` VARCHAR(16) NOT NULL DEFAULT 'MP4' COMMENT '流媒体封装格式: MP4, HLS, DASH',
    `target_codec` VARCHAR(16) NOT NULL DEFAULT 'H264' COMMENT '视频压缩编码: H264, H265, AV1',
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT '状态流转: PENDING, DOWNLOADING, TRANSCODING, UPLOADING, NOTIFYING, COMPLETED, FAILED',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试执行次数',
    `max_retries` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数上限',
    `output_file_id` CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '转码产物在 file_asset 中注册的文件ID',
    `output_file_size` BIGINT NULL COMMENT '转码产物字节大小',
    `output_bitrate` INT NULL COMMENT '产物实际视频码率 (kbps)',
    `output_fps` INT NULL COMMENT '产物实际视频帧率 (fps)',
    `output_width` INT NULL COMMENT '产物实际像素宽度',
    `output_height` INT NULL COMMENT '产物实际像素高度',
    `video_duration` INT NULL COMMENT '探测得出的视频实际时长 (秒)',
    `error_message` VARCHAR(1000) NULL COMMENT '异常失败详细信息摘要',
    `transcode_cost_ms` BIGINT NULL COMMENT 'FFmpeg 纯转码计算耗时 (毫秒)',
    `total_cost_ms` BIGINT NULL COMMENT '任务全生命周期总耗时 (毫秒)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_video_quality_format` (`video_id`, `target_quality`, `target_format`),
    KEY `idx_status_created` (`status`, `created_at`),
    KEY `idx_video_id` (`video_id`),
    CONSTRAINT `ck_transcode_quality` CHECK (`target_quality` IN ('360P', '480P', '720P', '1080P', '1080P_60', '4K')),
    CONSTRAINT `ck_transcode_format` CHECK (`target_format` IN ('MP4', 'HLS', 'DASH')),
    CONSTRAINT `ck_transcode_codec` CHECK (`target_codec` IN ('H264', 'H265', 'AV1')),
    CONSTRAINT `ck_transcode_status` CHECK (`status` IN ('PENDING', 'DOWNLOADING', 'TRANSCODING', 'UPLOADING', 'NOTIFYING', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频流转码调度任务工单表';

-- recommend-service：推荐服务视频特征向量存储表，仅由 recommend-service 持有并读写；
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

-- recommend-service: 推荐服务候选池视频元数据表，仅由 recommend-service 持有并读写；
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

-- recommend-service: 用户推荐兴趣画像与状态快照表
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

-- recommend-service: 用户明确屏蔽与负反馈约束表 (最高优先级硬过滤门禁)
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

-- recommend-service: 推荐模块原始行为反馈事实流水表 (不可篡改的行为日志账本)
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

-- user-service: 用户关注关系表，记录用户之间的单向关注拓扑；
-- 取消关注采用状态更新 (follow_status=0)，保障操作幂等并保留最后更新痕迹。
CREATE TABLE IF NOT EXISTS `user_follow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id` CHAR(32) NOT NULL COMMENT '关注者用户ID (发起人)',
    `follow_id` CHAR(32) NOT NULL COMMENT '被关注者用户ID (目标用户)',
    `follow_status` TINYINT NOT NULL DEFAULT 1 COMMENT '关注状态: 1=已关注, 0=已取消',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '初次关注时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后状态更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_follow` (`user_id`, `follow_id`),
    KEY `idx_user_following` (`user_id`, `follow_status`, `updated_at` DESC) COMMENT '我的关注列表高效分页',
    KEY `idx_follow_fans` (`follow_id`, `follow_status`, `updated_at` DESC) COMMENT '我的粉丝列表高效分页'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关注关系表';

-- user-service: 用户计数快照表，解耦高频并发关注产生的行锁争抢；
-- 计数字段由独立原子自增/自减更新，与 user_profile 基础资料及 revision 乐观锁隔离。
CREATE TABLE IF NOT EXISTS `user_counter` (
    `account_id` CHAR(32) NOT NULL COMMENT '用户ID (与 user_profile.account_id 一致)',
    `following_count` BIGINT NOT NULL DEFAULT 0 COMMENT '关注数',
    `follower_count` BIGINT NOT NULL DEFAULT 0 COMMENT '粉丝数',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
    PRIMARY KEY (`account_id`),
    CONSTRAINT `ck_user_counter_following` CHECK (`following_count` >= 0),
    CONSTRAINT `ck_user_counter_follower` CHECK (`follower_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户互动与关系计数表';

-- user-service：关注事件 Outbox 发件箱表；与 service/user-service/db/schema/user-outbox.sql 保持同一表结构。
CREATE TABLE IF NOT EXISTS `user_outbox` (
    `event_id` CHAR(36) NOT NULL COMMENT '稳定事件 UUID，重试和重放必须复用',
    `aggregate_id` CHAR(32) NOT NULL COMMENT '关联发起用户 ID (userId)',
    `event_type` VARCHAR(128) NOT NULL COMMENT '事件类型标识 (如 interaction.author-action)',
    `event_version` INT NOT NULL COMMENT '契约版本号',
    `payload` JSON NOT NULL COMMENT '符合推荐交互模板的事件载荷 JSON',
    `trace_id` VARCHAR(64) NULL COMMENT '链路追踪 ID',
    `occurred_at` DATETIME(3) NOT NULL COMMENT '事件发生时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, PUBLISHED, FAILED',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
    `next_attempt_at` DATETIME(3) NOT NULL COMMENT '下次重试时间',
    `lease_owner` VARCHAR(64) NULL COMMENT '当前租约所有者',
    `lease_until` DATETIME(3) NULL COMMENT '当前租约截止时间',
    `claim_token` CHAR(36) NULL COMMENT '认领令牌',
    `published_at` DATETIME(3) NULL COMMENT '成功发布时间',
    `last_error_code` VARCHAR(64) NULL COMMENT '最后错误分类',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`event_id`),
    KEY `idx_user_outbox_dispatch` (`status`, `next_attempt_at`, `lease_until`),
    KEY `idx_user_outbox_aggregate` (`aggregate_id`),
    CONSTRAINT `ck_user_outbox_status` CHECK (`status` IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user-service 领域事件 Outbox 发件箱表';

-- interaction-service: 视频互动统计计数聚合表 (高频交互主导者)
CREATE TABLE IF NOT EXISTS `interaction_video_counter` (
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `view_count` BIGINT NOT NULL DEFAULT 0 COMMENT '累计播放量',
    `like_count` BIGINT NOT NULL DEFAULT 0 COMMENT '累计点赞数',
    `star_count` BIGINT NOT NULL DEFAULT 0 COMMENT '累计收藏数',
    `share_count` BIGINT NOT NULL DEFAULT 0 COMMENT '累计分享数',
    `comment_count` BIGINT NOT NULL DEFAULT 0 COMMENT '累计评论数 (预留)',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`vid`),
    CONSTRAINT `ck_int_counter_view` CHECK (`view_count` >= 0),
    CONSTRAINT `ck_int_counter_like` CHECK (`like_count` >= 0),
    CONSTRAINT `ck_int_counter_star` CHECK (`star_count` >= 0),
    CONSTRAINT `ck_int_counter_share` CHECK (`share_count` >= 0),
    CONSTRAINT `ck_int_counter_comment` CHECK (`comment_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频互动统计计数聚合表';

-- interaction-service: 用户点赞事实与状态表
CREATE TABLE IF NOT EXISTS `interaction_like` (
    `id` CHAR(32) NOT NULL COMMENT '主键 UUID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `user_id` CHAR(32) NOT NULL COMMENT '用户账号ID',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '点赞状态: 1=已赞, 0=已取消',
    `version` BIGINT NOT NULL DEFAULT 1 COMMENT '状态版本号，每次状态反转递增',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_like_user_vid` (`user_id`, `vid`),
    KEY `idx_like_vid_status` (`vid`, `status`),
    KEY `idx_like_user_list` (`user_id`, `status`, `created_at` DESC),
    CONSTRAINT `ck_interaction_like_deleted` CHECK (`deleted` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频点赞记录表';

-- interaction-service: 用户收藏夹表
CREATE TABLE IF NOT EXISTS `interaction_star_folder` (
    `id` CHAR(32) NOT NULL COMMENT '收藏夹主键 UUID',
    `user_id` CHAR(32) NOT NULL COMMENT '用户账号ID',
    `title` VARCHAR(64) NOT NULL COMMENT '收藏夹标题',
    `is_default` TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认收藏夹: 1=是, 0=否',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1=正常, 0=已删除',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除',
    `active_title` VARCHAR(64) GENERATED ALWAYS AS (IF(`deleted` = 0, `title`, NULL)) VIRTUAL COMMENT '有效收藏夹标题虚拟列，用于唯一定界',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_folder_user_active_title` (`user_id`, `active_title`),
    KEY `idx_folder_user` (`user_id`, `status`),
    CONSTRAINT `ck_star_folder_deleted` CHECK (`deleted` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏夹表';

-- interaction-service: 收藏视频明细表
CREATE TABLE IF NOT EXISTS `interaction_star_item` (
    `id` CHAR(32) NOT NULL COMMENT '明细主键 UUID',
    `folder_id` CHAR(32) NOT NULL COMMENT '所属收藏夹ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `user_id` CHAR(32) NOT NULL COMMENT '用户账号ID (反查冗余)',
    `version` BIGINT NOT NULL DEFAULT 1 COMMENT '明细版本号，每次自愈复活递增',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_folder_vid` (`folder_id`, `vid`),
    KEY `idx_item_user_vid` (`user_id`, `vid`),
    KEY `idx_item_folder_time` (`folder_id`, `created_at` DESC),
    CONSTRAINT `ck_star_item_deleted` CHECK (`deleted` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏明细表';

-- interaction-service: 用户视频观看历史与心跳断点表
CREATE TABLE IF NOT EXISTS `interaction_watch_history` (
    `id` CHAR(32) NOT NULL COMMENT '记录主键 UUID',
    `user_id` CHAR(32) NOT NULL COMMENT '用户账号ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `last_position` INT NOT NULL DEFAULT 0 COMMENT '上次播放头进度 (秒)，用于断点续播',
    `watched_duration` INT NOT NULL DEFAULT 0 COMMENT '累计有效观看总时长 (秒)',
    `session_watched_duration` INT NOT NULL DEFAULT 0 COMMENT '当前观看会话累计有效观看时长 (秒)',
    `session_play_emitted` TINYINT NOT NULL DEFAULT 0 COMMENT '当前会话是否已经发送播放事件: 0=否, 1=是',
    `eligible_for_next_play` TINYINT NOT NULL DEFAULT 0 COMMENT '上一会话达到30%门槛从而允许下一次会话触发播放事件: 0=否, 1=是',
    `video_duration` INT NOT NULL DEFAULT 0 COMMENT '视频总时长 (秒)',
    `completed` TINYINT NOT NULL DEFAULT 0 COMMENT '是否完播: 1=是, 0=否',
    `first_watch_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次观看时间',
    `last_watch_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近一次心跳活跃时间',
    `last_valid_play_at` DATETIME(3) NULL COMMENT '最近一次计入有效播放并生成播放事件的时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_watch_user_vid` (`user_id`, `vid`),
    KEY `idx_watch_user_recent` (`user_id`, `last_watch_at` DESC),
    CONSTRAINT `ck_watch_history_deleted` CHECK (`deleted` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户视频观看历史与进度表';

-- interaction-service: 视频分享请求幂等防重记录表
CREATE TABLE IF NOT EXISTS `interaction_share_record` (
    `id` CHAR(32) NOT NULL COMMENT '主键 UUID',
    `idempotency_key` VARCHAR(64) NOT NULL COMMENT '客户端提供的请求幂等键',
    `user_id` CHAR(32) NOT NULL COMMENT '操作用户账号ID',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频公开业务短码',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未删除，1=逻辑删除',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_idempotency` (`idempotency_key`),
    KEY `idx_share_user_vid` (`user_id`, `vid`),
    CONSTRAINT `ck_share_record_deleted` CHECK (`deleted` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频分享请求幂等防重记录表';

-- interaction-service: 公开计数待汇总增量（仅真实业务状态变化时同事务写入）
CREATE TABLE IF NOT EXISTS `interaction_counter_delta` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '汇总顺序与主键',
    `vid` VARCHAR(32) NOT NULL COMMENT '视频业务编码',
    `counter_type` VARCHAR(16) NOT NULL COMMENT 'VIEW LIKE STAR SHARE',
    `delta` BIGINT NOT NULL COMMENT '计数增量',
    `source_type` VARCHAR(32) NOT NULL COMMENT '业务事实类型: LIKE_ACTIVE, LIKE_INACTIVE, STAR_ACTIVE, STAR_INACTIVE, WATCH_PLAY, SHARE',
    `source_id` VARCHAR(128) NOT NULL COMMENT '业务事实主键或幂等标识',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `processed_at` DATETIME(3) NULL COMMENT '汇总成功时间',
    PRIMARY KEY (`id`),
    KEY `idx_counter_delta_pending` (`processed_at`, `id`),
    UNIQUE KEY `uk_counter_delta_source` (`source_type`, `source_id`),
    CONSTRAINT `ck_counter_delta_type` CHECK (`counter_type` IN ('VIEW', 'LIKE', 'STAR', 'SHARE')),
    CONSTRAINT `ck_counter_delta_value` CHECK (
        (`counter_type` IN ('VIEW', 'SHARE') AND `delta` > 0)
        OR (`counter_type` IN ('LIKE', 'STAR') AND `delta` <> 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='互动公开计数待汇总增量';

-- interaction-service: 领域事件 Outbox 发件箱表
CREATE TABLE IF NOT EXISTS `interaction_outbox` (
    `event_id` CHAR(36) NOT NULL COMMENT '稳定事件 UUID，重试和重放必须复用',
    `aggregate_id` VARCHAR(64) NOT NULL COMMENT '关联业务聚合根 ID (如 vid 或 userId:vid)',
    `event_type` VARCHAR(128) NOT NULL COMMENT '事件类型标识 (固定为 interaction.video-action)',
    `event_version` INT NOT NULL COMMENT '契约版本号 (固定为 1)',
    `payload` JSON NOT NULL COMMENT '符合统一信封与载荷规范的事件 JSON',
    `trace_id` VARCHAR(64) NULL COMMENT '链路追踪 ID',
    `occurred_at` DATETIME(3) NOT NULL COMMENT '事件发生时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING, PROCESSING, PUBLISHED, FAILED',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
    `next_attempt_at` DATETIME(3) NOT NULL COMMENT '下次允许重试时间',
    `lease_owner` VARCHAR(64) NULL COMMENT '当前租约所有者实例标识',
    `lease_until` DATETIME(3) NULL COMMENT '当前租约截止时间',
    `claim_token` CHAR(36) NULL COMMENT '本次抢占认领令牌',
    `published_at` DATETIME(3) NULL COMMENT '成功发布时间',
    `last_error_code` VARCHAR(64) NULL COMMENT '最后一次投递失败错误分类',
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`event_id`),
    KEY `idx_interaction_outbox_dispatch` (`status`, `next_attempt_at`, `lease_until`),
    KEY `idx_interaction_outbox_aggregate` (`aggregate_id`),
    CONSTRAINT `ck_interaction_outbox_status` CHECK (`status` IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='interaction-service 领域事件 Outbox 发件箱表';

