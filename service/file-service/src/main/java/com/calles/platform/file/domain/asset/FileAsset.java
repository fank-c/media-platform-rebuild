package com.calles.platform.file.domain.asset;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.AllArgsConstructor;
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
@AllArgsConstructor
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

  /** 服务端实际流式读取的字节数；未完成或过期时为空。 */
  private Long size;

  /** 仅服务内部使用的对象 key，不向 HTTP DTO 暴露。 */
  private String storageKey;

  /** 此行对象使用的存储类型，不能随默认配置变化改写。 */
  private StorageType storageType;

  /** 服务端读取对象计算的低写十六进制 SHA-256；未完成时为空。 */
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

  /** 逻辑删除墓碑时间；为空表示仍可按生命周期继续处理。 */
  private Instant deletedAt;
}
