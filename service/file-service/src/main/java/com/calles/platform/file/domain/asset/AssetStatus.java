package com.calles.platform.file.domain.asset;

/** 文件资源的业务可用状态，独立于上传完成状态和逻辑删除墓碑。 */
public enum AssetStatus {
  /** 文件资源可继续被所属人读取、签名或删除。 */
  ACTIVE,
  /** 文件资源被业务禁用；首期不提供修改此状态的管理接口。 */
  DISABLED
}
