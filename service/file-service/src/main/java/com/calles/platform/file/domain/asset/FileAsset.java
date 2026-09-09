package com.calles.platform.file.domain.asset;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * file-service 独占的文件元数据聚合。
 *
 * <p>该实体只记录对象定位和生命周期事实，不承载内容、用户头像或公开引用等其他领域业务。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("file_asset")
public class FileAsset {
  /** 服务端生成的 32 位文件标识，永不由客户端指定。 */
  @TableId(value = "id", type = IdType.INPUT)
  private String id;

  /** 清理后的展示名称，不是对象路径且不得包含路径分隔符。 */
  private String originName;

  /** 客户端声明并规范化的 MIME；不代表安全内容检测结果。 */
  private String mime;

  /** 客户端声明的字节数，完成前不能当作真实对象大小。 */
  private long declaredSize;

  /** 服务端确认的对象字节数；未完成或过期时为空。 */
  private Long size;

  /** 仅服务内部使用的对象 key，不向 HTTP DTO 暴露。 */
  private String storageKey;

  /** 此行对象使用的存储类型，不能随默认配置变化改写。 */
  private StorageType storageType;

  /** 完成确认后的可信小写 SHA-256；未完成时为空。 */
  private String sha256;

  /** 资源是否可用，和上传状态、逻辑删除分别表达。 */
  private AssetStatus status;

  /** 直传确认生命周期。 */
  private UploadStatus uploadStatus;

  /** 直传确认截止时间；普通 multipart 上传可以为空。 */
  private Instant uploadExpiresAt;

  /** 创建该文件的认证主体 ID。 */
  private String createBy;

  /** 最后改变元数据的业务主体；后台任务沿用触发确认的主体。 */
  private String updateBy;

  /** UTC 创建时刻，由应用 Clock 注入。 */
  private Instant createAt;

  /** UTC 最后变更时刻，由应用 Clock 注入。 */
  private Instant updateAt;

  /** 删除闸门建立时刻；非空且墓碑为空表示对象删除或最终落墓碑仍在恢复中。 */
  private Instant deleteRequestedAt;

  /** 逻辑删除墓碑时间；仅在远端删除明确完成后写入。 */
  private Instant deletedAt;

  /** 记录创建时采用的上传协议，防止新旧确认路径混用。 */
  private UploadProtocol uploadProtocol;

  /** V2 直传的 staging 对象 key；旧协议和 multipart 记录为空。 */
  private String stagingStorageKey;

  /** V2 初始化时收到的客户端摘要，只有完成 CAS 后才复制到 sha256。 */
  private String expectedSha256;

  /** 首次进入 VERIFYING 的时间，用于恢复扫描和卡住观测。 */
  private Instant verificationRequestedAt;

  /** HEAD 暂存对象时观察到的 ETag，供条件 copy 固定源对象。 */
  private String verifiedSourceEtag;

  /** staging 对象已明确删除的时间；完成状态可暂时保留 NULL。 */
  private Instant stagingCleanedAt;

  /**
   * 兼容已有代码和旧记录测试的构造方法。
   *
   * <p>旧协议字段保持原顺序，新字段由调用方按协议显式设置。
   */
  public FileAsset(
      String id,
      String originName,
      String mime,
      long declaredSize,
      Long size,
      String storageKey,
      StorageType storageType,
      String sha256,
      AssetStatus status,
      UploadStatus uploadStatus,
      Instant uploadExpiresAt,
      String createBy,
      String updateBy,
      Instant createAt,
      Instant updateAt,
      Instant deleteRequestedAt,
      Instant deletedAt) {
    this.id = id;
    this.originName = originName;
    this.mime = mime;
    this.declaredSize = declaredSize;
    this.size = size;
    this.storageKey = storageKey;
    this.storageType = storageType;
    this.sha256 = sha256;
    this.status = status;
    this.uploadStatus = uploadStatus;
    this.uploadExpiresAt = uploadExpiresAt;
    this.createBy = createBy;
    this.updateBy = updateBy;
    this.createAt = createAt;
    this.updateAt = updateAt;
    this.deleteRequestedAt = deleteRequestedAt;
    this.deletedAt = deletedAt;
  }
}
