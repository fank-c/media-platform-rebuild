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

  /**
   * @param mapper 生命周期专用 Mapper
   */
  public MybatisFileAssetRepository(FileAssetMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * @return 插入行数
   */
  @Override
  public int insert(FileAsset asset) {
    return mapper.insert(asset);
  }

  /**
   * @return 未删除本人文件
   */
  @Override
  public Optional<FileAsset> findVisibleByIdAndOwner(String id, String owner) {
    return Optional.ofNullable(mapper.selectVisibleByIdAndOwner(id, owner));
  }

  /**
   * @return 包含墓碑的本人文件
   */
  @Override
  public Optional<FileAsset> findPhysicalByIdAndOwner(String id, String owner) {
    return Optional.ofNullable(mapper.selectPhysicalByIdAndOwner(id, owner));
  }

  /**
   * @return PENDING/VERIFYING 过期批次
   */
  @Override
  public List<FileAsset> findPendingExpired(
      Instant cutoff, Instant cursorTime, String cursorId, int limit) {
    return mapper.selectPendingExpired(cutoff, cursorTime, cursorId, limit);
  }

  /**
   * @return EXPIRED 批次
   */
  @Override
  public List<FileAsset> findExpired(String cursorId, int limit) {
    return mapper.selectExpired(cursorId, limit);
  }

  /**
   * @return 已建立删除闸门的批次
   */
  @Override
  public List<FileAsset> findDeletionRequested(Instant cursorTime, String cursorId, int limit) {
    return mapper.selectDeletionRequested(cursorTime, cursorId, limit);
  }

  /**
   * @return V2 VERIFYING 恢复批次
   */
  @Override
  public List<FileAsset> findVerificationRecovery(Instant cursorTime, String cursorId, int limit) {
    return mapper.selectVerificationRecovery(cursorTime, cursorId, limit);
  }

  /**
   * @return staging 清理批次
   */
  @Override
  public List<FileAsset> findStagingCleanup(String cursorId, int limit) {
    return mapper.selectStagingCleanup(cursorId, limit);
  }

  /**
   * @return 旧 V1 完成 CAS 行数
   */
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

  /**
   * @return V2 确认闸门抢占行数
   */
  @Override
  public int claimVerification(String id, String owner, Instant now) {
    return mapper.claimVerification(id, owner, now);
  }

  /**
   * @return ETag 观察写入行数
   */
  @Override
  public int observeVerification(String id, String owner, String etag, Instant now) {
    return mapper.observeVerification(id, owner, etag, now);
  }

  /**
   * @return V2 完成 CAS 行数
   */
  @Override
  public int completeVerification(
      String id,
      String owner,
      long expectedSize,
      long actualSize,
      String expectedSha256,
      String etag,
      StorageType storageType,
      String storageKey,
      String stagingKey,
      Instant now) {
    return mapper.completeVerification(
        id,
        owner,
        expectedSize,
        actualSize,
        expectedSha256,
        etag,
        storageType,
        storageKey,
        stagingKey,
        now);
  }

  /**
   * @return 过期 CAS 行数
   */
  @Override
  public int expirePending(String id, Instant now) {
    return mapper.expirePending(id, now);
  }

  /**
   * @return V2 内容不匹配作废行数
   */
  @Override
  public int rejectVerification(String id, Instant now) {
    return mapper.rejectVerification(id, now);
  }

  /**
   * @return V1 内容校验失败作废行数
   */
  @Override
  public int rejectPendingContent(String id, Instant now) {
    return mapper.rejectPendingContent(id, now);
  }

  /**
   * @return staging 清理标记行数
   */
  @Override
  public int markStagingCleaned(String id, String stagingKey, Instant now) {
    return mapper.markStagingCleaned(id, stagingKey, now);
  }

  /**
   * @return 删除闸门抢占行数
   */
  @Override
  public int requestDeletion(String id, String owner, Instant now) {
    return mapper.requestDeletion(id, owner, now);
  }

  /**
   * @return 逻辑删除墓碑写入行数
   */
  @Override
  public int completeDeletion(
      String id, String owner, StorageType storageType, String storageKey, Instant now) {
    return mapper.completeDeletion(id, owner, storageType, storageKey, now);
  }
}
