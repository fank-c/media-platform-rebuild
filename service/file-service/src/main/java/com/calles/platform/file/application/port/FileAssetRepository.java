package com.calles.platform.file.application.port;

import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.StorageType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 文件元数据持久化端口。
 *
 * <p>生命周期写入必须走带条件的专用方法并检查受影响行数，不能用通用 updateById 绕过状态机。
 */
public interface FileAssetRepository {
  /**
   * @param asset 要写入的新文件元数据
   * @return 成功插入的行数
   */
  int insert(FileAsset asset);

  /**
   * @param id 文件 ID
   * @param owner 所属主体
   * @return 未删除的本人文件
   */
  Optional<FileAsset> findVisibleByIdAndOwner(String id, String owner);

  /**
   * @param id 文件 ID
   * @return 未进入删除流程的文件记录（供通过时效防盗链验签的受控代理访问）
   */
  Optional<FileAsset> findVisibleById(String id);

  /**
   * @param id 文件 ID
   * @param owner 所属主体
   * @return 包含墓碑的本人文件，仅用于重复删除判断
   */
  Optional<FileAsset> findPhysicalByIdAndOwner(String id, String owner);

  /**
   * @return 已过期且仍 PENDING 或 VERIFYING 的一批记录
   */
  List<FileAsset> findPendingExpired(
      Instant cutoff, Instant cursorTime, String cursorId, int limit);

  /**
   * @return 已 EXPIRED 的一批残留对象记录，不包含已建立删除闸门的文件
   */
  List<FileAsset> findExpired(String cursorId, int limit);

  /**
   * @return 已建立删除闸门但尚未写入逻辑墓碑的一批记录
   */
  List<FileAsset> findDeletionRequested(Instant cursorTime, String cursorId, int limit);

  /**
   * @return 服务重启后仍处于 VERIFYING 的 V2 记录
   */
  List<FileAsset> findVerificationRecovery(Instant cursorTime, String cursorId, int limit);

  /**
   * @return 已完成但 staging 尚未清理的 V2 记录
   */
  List<FileAsset> findStagingCleanup(String cursorId, int limit);

  /** PENDING -> VERIFYING 的确认闸门 CAS。 */
  int claimVerification(String id, String owner, Instant now);

  /** 保存 HEAD 观察到的源 ETag。 */
  int observeVerification(String id, String owner, String etag, Instant now);

  /** VERIFYING -> COMPLETED 的条件完成写入。 */
  int completeVerification(
      String id,
      String owner,
      long expectedSize,
      long actualSize,
      String expectedSha256,
      String etag,
      StorageType storageType,
      String storageKey,
      String stagingKey,
      Instant now);

  /** V2 已证明对象内容不符合声明时立即作废，不等待 upload_expires_at。 */
  int rejectVerification(String id, Instant now);

  /** V1 已证明对象内容不符合声明时立即作废，不等待 upload_expires_at。 */
  int rejectPendingContent(String id, Instant now);

  /** V2 staging 已明确删除后记录清理时间。 */
  int markStagingCleaned(String id, String stagingKey, Instant now);

  /** 完成确认 CAS。 */
  int complete(
      String id,
      String owner,
      long expectedSize,
      long actualSize,
      String sha256,
      StorageType storageType,
      String storageKey,
      Instant now);

  /** 将未完成记录条件标记为过期。 */
  int expirePending(String id, Instant now);

  /**
   * 条件建立删除闸门；成功后普通查询、确认和读取必须立即隐藏该记录。
   *
   * @return 成功抢占删除处理权的行数
   */
  int requestDeletion(String id, String owner, Instant now);

  /**
   * 远端删除明确完成后条件写入逻辑墓碑；必须已经存在删除闸门。
   *
   * @return 成功落墓碑的行数
   */
  int completeDeletion(
      String id, String owner, StorageType storageType, String storageKey, Instant now);
}
