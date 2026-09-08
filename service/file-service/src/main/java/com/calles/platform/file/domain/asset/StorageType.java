package com.calles.platform.file.domain.asset;

/**
 * 文件对象所在的存储实现类型。
 *
 * <p>首期只注册 MINIO；已有记录使用其行内值路由，不能因默认配置变化被静默迁移。
 */
public enum StorageType {
  /** 使用私有 MinIO bucket 保存对象。 */
  MINIO
}
