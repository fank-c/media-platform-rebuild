package com.calles.platform.file.domain.asset;

/**
 * 对象上传生命周期。
 *
 * <p>PENDING 只能通过条件更新进入 COMPLETED 或 EXPIRED，禁止恢复过期记录避免旧签名复用。
 */
public enum UploadStatus {
  /** 已建立直传元数据，尚未由服务端核验对象内容。 */
  PENDING,
  /** 服务端已在有限读取中确认长度和 SHA-256。 */
  COMPLETED,
  /** 上传期限结束或已证明内容不符合声明，等待受控清理。 */
  EXPIRED
}
