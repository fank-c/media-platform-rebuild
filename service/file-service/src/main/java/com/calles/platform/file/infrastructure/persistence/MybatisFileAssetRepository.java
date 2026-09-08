package com.calles.platform.file.infrastructure.persistence;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.StorageType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 专用 SQL 的文件元数据仓储实现。 */
@Repository
public class MybatisFileAssetRepository implements FileAssetRepository {
  /** file_asset Mapper。 */
  private final FileAssetMapper mapper;

  /** @param mapper 生命周期专用 Mapper */
  public MybatisFileAssetRepository(FileAssetMapper mapper) {
    this.mapper = mapper;
  }

  /** @return 插入行数 */
  @Override
  public int insert(FileAsset asset) {
    return mapper.insert(asset);
  }

  /** @return 未删除本人文件 */
  @Override
  public Optional<FileAsset> findVisibleByIdAndOwner(String id, String owner) {
    return Optional.ofNullable(mapper.selectVisibleByIdAndOwner(id, owner));
  }

  /** @return 包含墓碑的本人文件 */
  @Override
  public Optional<FileAsset> findPhysicalByIdAndOwner(String id, String owner) {
    return Optional.ofNullable(mapper.selectPhysicalByIdAndOwner(id, owner));
  }

  /** @return PENDING 过期批次 */
  @Override
  public List<FileAsset> findPendingExpired(
      Instant cutoff, Instant cursorTime, String cursorId, int limit) {
    return mapper.selectPendingExpired(cutoff, cursorTime, cursorId, limit);
  }

  /** @return EXPIRED 批次 */
  @Override
  public List<FileAsset> findExpired(String cursorId, int limit) {
    return mapper.selectExpired(cursorId, limit);
  }

  /** @return 完成 CAS 行数 */
  @Override
  public int complete(
      String id,
      String owner,
      long expectedSize,
      long actualSize,
      String sha256,
      StorageType storageType,
      String storageKey,
      Instant now) {
    return mapper.complete(
        id, owner, expectedSize, actualSize, sha256, storageType, storageKey, now);
  }

  /** @return 过期 CAS 行数 */
  @Override
  public int expirePending(String id, Instant now) {
    return mapper.expirePending(id, now);
  }

  /** @return 逻辑删除行数 */
  @Override
  public int markDeleted(
      String id, String owner, StorageType storageType, String storageKey, Instant now) {
    return mapper.markDeleted(id, owner, storageType, storageKey, now);
  }
}
