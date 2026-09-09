package com.calles.platform.file.domain.asset;

/**
 * 对象上传生命周期。
 *
 * <p>V2 直传必须先通过 PENDING -> VERIFYING 的数据库 CAS，再执行 HEAD 和条件 copy；因此确认中的记录 不会与过期清理、删除或第二个 confirm
 * 共享可写状态。
 */
public enum UploadStatus {
  /** 已建立直传元数据，尚未由服务端核验对象内容。 */
  PENDING,
  /** 已抢占确认闸门，正在观察 staging 对象并迁移到 permanent。 */
  VERIFYING,
  /** 已完成确认并可被读取。 */
  COMPLETED,
  /** 上传期限结束或已证明内容不符合声明，等待受控清理。 */
  EXPIRED
}
