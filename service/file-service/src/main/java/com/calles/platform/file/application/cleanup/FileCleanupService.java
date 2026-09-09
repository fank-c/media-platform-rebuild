package com.calles.platform.file.application.cleanup;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.exception.ObjectStorageException;
import com.calles.platform.file.infrastructure.observability.FileOperationalMetrics;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 过期记录、V2 VERIFYING 恢复、staging 残留和删除闸门的受控批次清理用例。
 *
 * <p>该能力默认关闭，不会扫描无元数据的对象，更不会全桶删除；
 * 所有对象 key 都来自本服务数据库。
 */
@Service
public class FileCleanupService {
  /** 元数据仓储。 */
  private final FileAssetRepository repository;

  /** 存储工厂。 */
  private final StorageFactory storageFactory;

  /** 文件大小和 V2 运行约束。 */
  private final FileStorageProperties properties;

  /** UTC 时钟。 */
  private final Clock clock;

  /** 脱敏指标。 */
  private final FileOperationalMetrics metrics;

  /**
   * 兼容旧测试和调用方的构造入口；V2 恢复使用默认大小预算。
   *
   * @param repository 仓储
   * @param storageFactory 工厂
   * @param clock 时钟
   * @param metrics 指标
   */
  public FileCleanupService(
      FileAssetRepository repository,
      StorageFactory storageFactory,
      Clock clock,
      FileOperationalMetrics metrics) {
    this(repository, storageFactory, new FileStorageProperties(), clock, metrics);
  }

  /**
   * @param repository 仓储
   * @param storageFactory 工厂
   * @param properties 文件参数
   * @param clock 时钟
   * @param metrics 指标
   */
  public FileCleanupService(
      FileAssetRepository repository,
      StorageFactory storageFactory,
      FileStorageProperties properties,
      Clock clock,
      FileOperationalMetrics metrics) {
    this.repository = repository;
    this.storageFactory = storageFactory;
    this.properties = properties;
    this.clock = clock;
    this.metrics = metrics;
  }

  /** 使用固定 cutoff 和 keyset 有界处理 PENDING/VERIFYING，再重试 EXPIRED 残留。 */
  public void cleanup(int batchSize, int maxBatches, Duration runTimeout) {
    Instant cutoff = Instant.now(clock);
    Instant deadline = cutoff.plus(runTimeout);
    Instant cursorTime = null;
    String cursorId = null;
    for (int batch = 0; batch < maxBatches && hasBudget(deadline); batch++) {
      List<FileAsset> assets =
          repository.findPendingExpired(cutoff, cursorTime, cursorId, batchSize);
      if (assets.isEmpty()) break;
      for (FileAsset asset : assets) {
        if (!hasBudget(deadline)) return;
        expireAndDelete(asset, cutoff);
      }
      FileAsset last = assets.get(assets.size() - 1);
      cursorTime = last.getUploadExpiresAt();
      cursorId = last.getId();
    }
    String expiredCursor = null;
    for (int batch = 0; batch < maxBatches && hasBudget(deadline); batch++) {
      List<FileAsset> assets = repository.findExpired(expiredCursor, batchSize);
      if (assets.isEmpty()) break;
      for (FileAsset asset : assets) {
        if (!hasBudget(deadline)) return;
        deleteExpiredObject(asset);
      }
      expiredCursor = assets.get(assets.size() - 1).getId();
    }
  }

  /** 恢复 V2 VERIFYING：检查 staging 后继续条件 copy 或在到期后双位置清理。 */
  public void recoverVerification(int batchSize, int maxBatches, Duration runTimeout) {
    Instant deadline = Instant.now(clock).plus(runTimeout);
    Instant cursorTime = null;
    String cursorId = null;
    for (int batch = 0; batch < maxBatches && hasBudget(deadline); batch++) {
      List<FileAsset> assets = repository.findVerificationRecovery(cursorTime, cursorId, batchSize);
      if (assets.isEmpty()) return;
      for (FileAsset asset : assets) {
        if (!hasBudget(deadline)) return;
        recoverVerificationOne(asset);
      }
      FileAsset last = assets.get(assets.size() - 1);
      cursorTime = last.getVerificationRequestedAt();
      cursorId = last.getId();
    }
  }

  /** 完成后 staging 删除失败的补偿扫描；不回滚已完成文件。 */
  public void cleanupStaging(int batchSize, int maxBatches, Duration runTimeout) {
    Instant deadline = Instant.now(clock).plus(runTimeout);
    String cursorId = null;
    for (int batch = 0; batch < maxBatches && hasBudget(deadline); batch++) {
      List<FileAsset> assets = repository.findStagingCleanup(cursorId, batchSize);
      if (assets.isEmpty()) return;
      for (FileAsset asset : assets) {
        if (!hasBudget(deadline)) return;
        cleanupStagingOne(asset);
      }
      cursorId = assets.get(assets.size() - 1).getId();
    }
  }

  /** 独立扫描删除闸门已建立但尚未落墓碑的记录，重试删除 permanent 与 staging。 */
  public void recoverDeletion(int batchSize, int maxBatches, Duration runTimeout) {
    Instant deadline = Instant.now(clock).plus(runTimeout);
    Instant cursorTime = null;
    String cursorId = null;
    for (int batch = 0; batch < maxBatches && hasBudget(deadline); batch++) {
      List<FileAsset> assets = repository.findDeletionRequested(cursorTime, cursorId, batchSize);
      if (assets.isEmpty()) return;
      for (FileAsset asset : assets) {
        if (!hasBudget(deadline)) return;
        recoverOneDeletion(asset);
      }
      FileAsset last = assets.get(assets.size() - 1);
      cursorTime = last.getDeleteRequestedAt();
      cursorId = last.getId();
    }
  }

  /** 对 V2 VERIFYING 记录执行一次不读正文的恢复。 */
  private void recoverVerificationOne(FileAsset asset) {
    metrics.verificationStuck();
    Instant now = Instant.now(clock);
    if (asset.getUploadExpiresAt() == null || !asset.getUploadExpiresAt().isAfter(now)) {
      if (repository.expirePending(asset.getId(), now) == 1) deleteExpiredObject(asset);
      return;
    }
    ObjectStorageClient client = storageFactory.require(asset.getStorageType());
    ObjectStorageClient.ObjectHead head;
    try {
      // 恢复只依赖 HEAD；即使服务重启也不能退化为 GET 全量计算摘要。
      head = client.head(asset.getStagingStorageKey());
    } catch (ObjectStorageException exception) {
      metrics.verificationFailure();
      return;
    }
    if (head.size() != asset.getDeclaredSize()
        || head.size() < 1
        || head.size() > properties.getMaxSize()
        || head.etag() == null
        || head.etag().isBlank()) {
      metrics.verificationFailure();
      if (repository.rejectVerification(asset.getId(), Instant.now(clock)) == 1) {
        deleteExpiredObject(asset);
      }
      return;
    }
    if (repository.observeVerification(
            asset.getId(), asset.getCreateBy(), head.etag(), Instant.now(clock))
        != 1) {
      return;
    }
    try {
      client.copy(asset.getStagingStorageKey(), asset.getStorageKey(), head.etag());
    } catch (ObjectStorageException exception) {
      metrics.verificationFailure();
      if (exception.getCategory() == ObjectStorageException.Category.CONTENT_MISMATCH
          && repository.rejectVerification(asset.getId(), Instant.now(clock)) == 1) {
        deleteExpiredObject(asset);
      }
      return;
    }
    if (repository.completeVerification(
            asset.getId(),
            asset.getCreateBy(),
            asset.getDeclaredSize(),
            head.size(),
            asset.getExpectedSha256(),
            head.etag(),
            asset.getStorageType(),
            asset.getStorageKey(),
            asset.getStagingStorageKey(),
            Instant.now(clock))
        == 1) {
      metrics.verificationSuccess();
      cleanupStagingOne(asset);
    } else {
      // 若记录已过期或已落删除墓碑，copy 可能晚于状态变化，必须清掉新产生的对象。
      FileAsset current =
          repository.findPhysicalByIdAndOwner(asset.getId(), asset.getCreateBy()).orElse(null);
      if (current != null
          && current.getUploadStatus()
              != com.calles.platform.file.domain.asset.UploadStatus.VERIFYING) {
        deleteVerificationObjects(asset);
      }
    }
  }

  /** 删除确认竞争中已生成的两个对象，避免过期或删除状态下留下 permanent 孤儿。 */
  private void deleteVerificationObjects(FileAsset asset) {
    ObjectStorageClient client = storageFactory.require(asset.getStorageType());
    deleteForCleanup(client, asset.getStorageKey());
    if (asset.getStagingStorageKey() != null
        && !asset.getStagingStorageKey().equals(asset.getStorageKey())) {
      deleteForCleanup(client, asset.getStagingStorageKey());
    }
  }

  /** 对删除中的单行重复执行两个远端删除，并仅在均明确收敛后写墓碑。 */
  private void recoverOneDeletion(FileAsset asset) {
    metrics.deletionStuck();
    ObjectStorageClient client = storageFactory.require(asset.getStorageType());
    if (!deleteForRecovery(client, asset.getStorageKey())) return;
    if (asset.getStagingStorageKey() != null
        && !asset.getStagingStorageKey().equals(asset.getStorageKey())
        && !deleteForRecovery(client, asset.getStagingStorageKey())) {
      return;
    }
    if (repository.completeDeletion(
            asset.getId(),
            asset.getCreateBy(),
            asset.getStorageType(),
            asset.getStorageKey(),
            Instant.now(clock))
        == 1) {
      metrics.deletionSuccess();
    }
  }

  /** 过期 CAS 成功后处理两个对象位置，防止 V2 permanent 遗留。 */
  private void expireAndDelete(FileAsset asset, Instant now) {
    if (repository.expirePending(asset.getId(), now) == 1) deleteExpiredObject(asset);
  }

  /** 删除 EXPIRED 远端对象；明确不存在也视为收敛。 */
  private void deleteExpiredObject(FileAsset asset) {
    ObjectStorageClient client = storageFactory.require(asset.getStorageType());
    deleteForCleanup(client, asset.getStorageKey());
    if (asset.getStagingStorageKey() != null
        && !asset.getStagingStorageKey().equals(asset.getStorageKey())) {
      deleteForCleanup(client, asset.getStagingStorageKey());
    }
  }

  /** 完成后尽力删除 staging；失败交给下一轮扫描和生命周期规则。 */
  private void cleanupStagingOne(FileAsset asset) {
    try {
      storageFactory.require(asset.getStorageType()).delete(asset.getStagingStorageKey());
      repository.markStagingCleaned(
          asset.getId(), asset.getStagingStorageKey(), Instant.now(clock));
    } catch (ObjectStorageException exception) {
      if (exception.getCategory() == ObjectStorageException.Category.NOT_FOUND) {
        repository.markStagingCleaned(
            asset.getId(), asset.getStagingStorageKey(), Instant.now(clock));
      } else {
        metrics.stagingCleanupFailure();
      }
    }
  }

  /** 恢复删除允许明确不存在，其他失败保留删除闸门。 */
  private boolean deleteForRecovery(ObjectStorageClient client, String key) {
    try {
      client.delete(key);
      return true;
    } catch (ObjectStorageException exception) {
      if (exception.getCategory() == ObjectStorageException.Category.NOT_FOUND) return true;
      metrics.deletionFailure();
      return false;
    }
  }

  /** 普通过期清理允许明确不存在，其他失败保留 EXPIRED 供下一轮重试。 */
  private void deleteForCleanup(ObjectStorageClient client, String key) {
    try {
      client.delete(key);
      metrics.cleanupSuccess();
    } catch (ObjectStorageException exception) {
      if (exception.getCategory() == ObjectStorageException.Category.NOT_FOUND) {
        metrics.cleanupSuccess();
      } else {
        metrics.cleanupFailure();
      }
    }
  }

  /**
   * @param deadline 本轮截止时间 @return 是否仍可开始下一条远端操作
   */
  private boolean hasBudget(Instant deadline) {
    return Instant.now(clock).isBefore(deadline);
  }
}
