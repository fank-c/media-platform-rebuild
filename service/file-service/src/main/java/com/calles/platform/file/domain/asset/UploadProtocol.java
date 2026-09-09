package com.calles.platform.file.domain.asset;

/**
 * 文件上传协议版本。
 *
 * <p>协议标识保存在每条记录中，避免旧直传记录误走 staging + checksum 确认流程。
 */
public enum UploadProtocol {
  /** 现有直传协议：PUT 直接写入 storage_key，确认时兼容服务端流式摘要。 */
  LEGACY_V1,
  /** 服务端 multipart 上传，写入 permanent key 后直接落 COMPLETED。 */
  SERVER_MULTIPART_V1,
  /** 新直传协议：checksum 绑定 staging PUT，确认时条件复制到 permanent。 */
  DIRECT_STAGED_CHECKSUM_V2
}
