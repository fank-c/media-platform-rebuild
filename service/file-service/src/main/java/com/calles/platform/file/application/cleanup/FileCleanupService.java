package com.calles.platform.file.application.cleanup;

import com.calles.platform.file.application.port.FileAssetRepository;
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
 * 过期 PENDING 与 EXPIRED 残留对象的受控批次清理用例。
 *
 * <p>该能力默认关闭，不会扫描无元数据的对象，更不会全桶删除；EXPIRED 保留为下一轮重试锚点。
 */
@Service
public class FileCleanupService {
  /** 元数据仓储。 */
  private final FileAssetRepository repository;

  /** 存储工厂。 */
  private final StorageFactory storageFactory;

  /** UTC 时钟。 */
  private final Clock clock;

  /** 脱敏指标。 */
  private final FileOperationalMetrics metrics;

  /**
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
    this.repository = repository;
    this.storageFactory = storageFactory;
    this.clock = clock;
    this.metrics = metrics;
  }

  /**
   * 使用固定 cutoff 和 keyset 有界处理 PENDING，再以稳定 ID 游标重试 EXPIRED。
   *
   * @param batchSize 每批上限
   * @param maxBatches 本轮批次数上限
   * @param runTimeout 单轮总预算，到期后由下次调度继续
   */
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

  /**
   * @param deadline 本轮截止时间
   * @return 是否仍可开始下一条远端操作
   */
  private boolean hasBudget(Instant deadline) {
    return Instant.now(clock).isBefore(deadline);
  }

  /** 先用条件 SQL 抢占 PENDING，避免清理并发删除正常已完成记录。 */
  private void expireAndDelete(FileAsset asset, Instant now) {
    if (repository.expirePending(asset.getId(), now) == 1) deleteExpiredObject(asset);
  }

  /** 删除远端对象；明确不存在也可计为收敛，其他失败留 EXPIRED 供下次重试。 */
  private void deleteExpiredObject(FileAsset asset) {
    try {
      storageFactory.require(asset.getStorageType()).delete(asset.getStorageKey());
      metrics.cleanupSuccess();
    } catch (ObjectStorageException exception) {
      if (exception.getCategory() == ObjectStorageException.Category.NOT_FOUND)
        metrics.cleanupSuccess();
      else metrics.cleanupFailure();
    }
  }
}
