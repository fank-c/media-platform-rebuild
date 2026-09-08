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
   * @param owner 所属主体
   * @return 包含墓碑的本人文件，仅用于重复删除判断
   */
  Optional<FileAsset> findPhysicalByIdAndOwner(String id, String owner);

  /** @return 已过期且仍 PENDING 的一批记录 */
  List<FileAsset> findPendingExpired(
      Instant cutoff, Instant cursorTime, String cursorId, int limit);

  /** @return 已 EXPIRED 的一批残留对象记录 */
  List<FileAsset> findExpired(String cursorId, int limit);

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

  /** 远端删除后写入逻辑墓碑。 */
  int markDeleted(String id, String owner, StorageType storageType, String storageKey, Instant now);
}
