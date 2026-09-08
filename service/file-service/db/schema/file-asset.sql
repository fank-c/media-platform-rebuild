-- file-service 独占 file_asset 表；执行前后必须用 SHOW CREATE TABLE 对照，已存在差异时停止而非隐式 ALTER。
CREATE TABLE IF NOT EXISTS file_asset (
  id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的文件ID',
  origin_name VARCHAR(255) NOT NULL COMMENT '清理后的展示文件名，不是对象路径',
  mime VARCHAR(127) NOT NULL COMMENT '声明并规范化的MIME，不代表安全鉴定',
  declared_size BIGINT NOT NULL COMMENT '客户端声明字节数，完成前不是真实大小',
  size BIGINT NULL COMMENT '服务端实际读取字节数，未完成为空',
  storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务内对象key，不对外暴露',
  storage_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对象存储类型，首期仅MINIO',
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '完成读取时计算的小写SHA-256',
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '资源启用状态',
  upload_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT '上传确认状态',
  upload_expires_at DATETIME(3) NULL COMMENT 'PENDING确认期限，UTC',
  create_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建认证主体ID',
  update_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '最后业务操作者ID',
  create_at DATETIME(3) NOT NULL COMMENT 'UTC创建时刻',
  update_at DATETIME(3) NOT NULL COMMENT 'UTC最后变更时刻',
  deleted_at DATETIME(3) NULL COMMENT '逻辑删除墓碑时间，NULL为未删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_file_storage (storage_type, storage_key),
  KEY idx_file_owner (create_by, status, create_at, id),
  KEY idx_file_expiry (upload_status, deleted_at, upload_expires_at, id),
  CONSTRAINT ck_file_size CHECK (declared_size > 0 AND
    ((upload_status = 'COMPLETED' AND size IS NOT NULL AND size = declared_size) OR
     (upload_status IN ('PENDING', 'EXPIRED') AND size IS NULL))),
  CONSTRAINT ck_file_storage CHECK (storage_type IN ('MINIO')),
  CONSTRAINT ck_file_upload CHECK (upload_status IN ('PENDING', 'COMPLETED', 'EXPIRED')),
  CONSTRAINT ck_file_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT ck_file_digest CHECK (
    (upload_status = 'COMPLETED' AND sha256 IS NOT NULL AND CHAR_LENGTH(sha256) = 64) OR
    (upload_status IN ('PENDING', 'EXPIRED') AND sha256 IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='file-service 独占的文件元数据';
