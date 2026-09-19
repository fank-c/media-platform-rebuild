package com.calles.platform.file.domain.asset;

/**
 * 文件对象所在的存储实现类型。
 *
 * <p>支持多存储策略（MINIO / ALIYUN_OSS），运行时通过 StorageFactory 动态路由。
 */
public enum StorageType {
  /** 使用 MinIO 保存对象（适用于本地开发或历史兼容）。 */
  MINIO,

  /** 使用阿里云 OSS 私有 Bucket 保存对象（云上主策略）。 */
  ALIYUN_OSS
}

