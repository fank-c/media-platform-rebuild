-- file-service 独占 file_asset 表；执行前后必须用 SHOW CREATE TABLE 对照，已存在差异时停止而非隐式 ALTER。
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
