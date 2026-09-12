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
  storage_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对象存储类型，首期仅MINIO',
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
  CONSTRAINT ck_file_storage CHECK (storage_type IN ('MINIO')),
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
