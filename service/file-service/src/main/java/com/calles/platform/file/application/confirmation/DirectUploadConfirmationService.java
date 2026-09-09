package com.calles.platform.file.application.confirmation;

import com.calles.platform.file.application.asset.FileAssetViews;
import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.AssetStatus;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.UploadProtocol;
import com.calles.platform.file.domain.asset.UploadStatus;
import com.calles.platform.file.exception.FileOperationException;
import com.calles.platform.file.exception.ObjectStorageException;
import com.calles.platform.file.infrastructure.observability.FileOperationalMetrics;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import com.calles.platform.file.interfaces.http.dto.FileResponses;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 预签名直传确认用例。
 *
 * <p>V1 保留服务端流式摘要兼容路径；V2 先以数据库 CAS 建立 VERIFYING 闸门，
 * 再 HEAD staging、条件 copy 到 permanent，确认过程绝不打开 staging 全文件流。
 */
@Service
public class DirectUploadConfirmationService {
  /** 元数据仓储。 */
  private final FileAssetRepository repository;

  /** 存储适配器工厂。 */
  private final StorageFactory storageFactory;

  /** 专用有界确认线程池。 */
  private final ThreadPoolExecutor executor;

  /** 资源参数。 */
  private final FileStorageProperties properties;

  /** UTC 时钟。 */
  private final Clock clock;

  /** 脱敏指标。 */
  private final FileOperationalMetrics metrics;

  /** 同进程已接收或正在运行的 fileId 集合。 */
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  /**
   * @param repository 仓储
   * @param storageFactory 工厂
   * @param fileConfirmExecutor 有界执行器
   * @param properties 文件资源参数
   * @param clock UTC 时钟
   * @param metrics 脱敏运行指标
   */
  public DirectUploadConfirmationService(
      FileAssetRepository repository,
      StorageFactory storageFactory,
      ThreadPoolExecutor fileConfirmExecutor,
      FileStorageProperties properties,
      Clock clock,
      FileOperationalMetrics metrics) {
    this.repository = repository;
    this.storageFactory = storageFactory;
    this.executor = fileConfirmExecutor;
    this.properties = properties;
    this.clock = clock;
    this.metrics = metrics;
  }

  /**
   * 兼容 V1：异步读取 legacy 对象并由服务端计算 SHA-256。
   *
   * @param owner 当前用户 ID
   * @param id 文件 ID
   * @return 已完成元数据，或真实 202 的异步状态
   */
  public Object confirm(String owner, String id) {
    FileAsset asset = requireVisible(owner, id);
    if (asset.getUploadStatus() == UploadStatus.COMPLETED) return FileAssetViews.metadata(asset);
    if (asset.getUploadProtocol() == UploadProtocol.DIRECT_STAGED_CHECKSUM_V2) {
      return confirmV2(owner, id);
    }
    Instant now = Instant.now(clock);
    if (asset.getUploadStatus() == UploadStatus.EXPIRED
        || asset.getUploadExpiresAt() == null
        || !asset.getUploadExpiresAt().isAfter(now)) {
      repository.expirePending(id, now);
      throw new FileOperationException(HttpStatus.GONE, "上传确认已过期");
    }
    if (!inFlight.add(id)) return accepted(id, UploadStatus.PENDING);
    String traceId = MDC.get("traceId");
    try {
      // 拒绝时立即移除标记，保留 PENDING 供客户端在有余量后重新确认。
      executor.execute(() -> verifyLegacy(owner, id, traceId));
      return accepted(id, UploadStatus.PENDING);
    } catch (RejectedExecutionException exception) {
      inFlight.remove(id);
      metrics.confirmationRejected();
      throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "文件确认队列繁忙，请稍后重试", exception);
    }
  }

  /**
   * V2 确认：先抢占 PENDING -> VERIFYING，再异步执行 HEAD + 条件 copy。
   *
   * @param owner 当前用户 ID
   * @param id 文件 ID
   * @return COMPLETED 元数据或 VERIFYING 的 202 状态
   */
  public Object confirmV2(String owner, String id) {
    FileAsset asset = requireVisible(owner, id);
    if (asset.getUploadProtocol() != UploadProtocol.DIRECT_STAGED_CHECKSUM_V2) {
      throw new FileOperationException(HttpStatus.CONFLICT, "文件不是 V2 直传记录");
    }
    if (asset.getUploadStatus() == UploadStatus.COMPLETED) return FileAssetViews.metadata(asset);
    Instant now = Instant.now(clock);
    if (asset.getUploadStatus() == UploadStatus.EXPIRED
        || asset.getUploadExpiresAt() == null
        || !asset.getUploadExpiresAt().isAfter(now)) {
      repository.expirePending(id, now);
      throw new FileOperationException(HttpStatus.GONE, "上传确认已过期");
    }
    if (asset.getUploadStatus() == UploadStatus.PENDING) {
      if (repository.claimVerification(id, owner, now) == 1) {
        metrics.verificationRequested();
      } else {
        asset = requireVisible(owner, id);
        if (asset.getUploadStatus() == UploadStatus.COMPLETED) {
          return FileAssetViews.metadata(asset);
        }
        if (asset.getUploadStatus() == UploadStatus.EXPIRED) {
          throw new FileOperationException(HttpStatus.GONE, "上传确认已过期");
        }
      }
    }
    enqueueV2(owner, id);
    return accepted(id, UploadStatus.VERIFYING);
  }

  /**
   * @param id 文件 ID @param status 当前异步状态 @return 建议客户端轮询的 202 状态
   */
  private FileResponses.ConfirmationAccepted accepted(String id, UploadStatus status) {
    return new FileResponses.ConfirmationAccepted(id, status.name(), 2L);
  }

  /** 将 V2 任务放入有界池；失败时不清除 VERIFYING，交给恢复扫描继续。 */
  private void enqueueV2(String owner, String id) {
    if (!inFlight.add(id)) return;
    String traceId = MDC.get("traceId");
    try {
      executor.execute(() -> verifyV2(owner, id, traceId));
    } catch (RejectedExecutionException exception) {
      inFlight.remove(id);
      metrics.confirmationRejected();
      throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "文件确认队列繁忙，请稍后重试", exception);
    }
  }

  /** V1 旧确认路径：有限预算内读取对象并计算摘要，供兼容窗口自然收敛。 */
  private void verifyLegacy(String owner, String id, String traceId) {
    if (traceId != null) MDC.put("traceId", traceId);
    try {
      // 兼容路径保留原行为；V2 分支严禁进入此方法。
      new LegacyVerifier(repository, storageFactory, properties, clock, metrics).verify(owner, id);
    } catch (RuntimeException exception) {
      metrics.operationFailure();
    } finally {
      inFlight.remove(id);
      MDC.remove("traceId");
    }
  }

  /** V2 只执行 HEAD、ETag 条件 copy 和数据库 CAS，不读取完整 staging 内容。 */
  private void verifyV2(String owner, String id, String traceId) {
    if (traceId != null) MDC.put("traceId", traceId);
    try {
      FileAsset asset = repository.findVisibleByIdAndOwner(id, owner).orElse(null);
      if (asset == null
          || asset.getUploadProtocol() != UploadProtocol.DIRECT_STAGED_CHECKSUM_V2
          || asset.getUploadStatus() != UploadStatus.VERIFYING
          || asset.getStatus() != AssetStatus.ACTIVE) {
        return;
      }
      Instant now = Instant.now(clock);
      if (asset.getUploadExpiresAt() == null || !asset.getUploadExpiresAt().isAfter(now)) {
        repository.expirePending(id, now);
        return;
      }
      ObjectStorageClient client = storageFactory.require(asset.getStorageType());
      ObjectStorageClient.ObjectHead head;
      try {
        // HEAD 只获取大小和 ETag，不创建对象内容流。
        head = client.head(asset.getStagingStorageKey());
      } catch (ObjectStorageException exception) {
        if (exception.getCategory() == ObjectStorageException.Category.NOT_FOUND) {
          metrics.verificationFailure();
          return;
        }
        metrics.verificationFailure();
        return;
      }
      if (head.size() != asset.getDeclaredSize()
          || head.size() < 1
          || head.size() > properties.getMaxSize()
          || head.etag() == null
          || head.etag().isBlank()) {
        metrics.verificationFailure();
        repository.rejectVerification(id, Instant.now(clock));
        return;
      }
      if (repository.observeVerification(id, owner, head.etag(), Instant.now(clock)) != 1) {
        return;
      }
      try {
        // ETag 条件由 MinIO/OSS 在服务端执行，HEAD 与 copy 间被覆盖时 copy 必须失败。
        client.copy(asset.getStagingStorageKey(), asset.getStorageKey(), head.etag());
      } catch (ObjectStorageException exception) {
        if (exception.getCategory() == ObjectStorageException.Category.CONTENT_MISMATCH) {
          metrics.verificationFailure();
          repository.rejectVerification(id, Instant.now(clock));
          return;
        }
        metrics.verificationFailure();
        return;
      }
      int completed =
          repository.completeVerification(
              id,
              owner,
              asset.getDeclaredSize(),
              head.size(),
              asset.getExpectedSha256(),
              head.etag(),
              asset.getStorageType(),
              asset.getStorageKey(),
              asset.getStagingStorageKey(),
              Instant.now(clock));
      if (completed == 1) {
        metrics.verificationSuccess();
        cleanupStaging(asset, client);
      } else {
        // CAS 失败若是删除或过期已经收敛，必须补删 copy 产物。
        // 只有数据库结果未知时，才留给恢复任务继续判断。
        metrics.verificationFailure();
        FileAsset current = repository.findPhysicalByIdAndOwner(id, owner).orElse(null);
        if (current != null && current.getUploadStatus() != UploadStatus.VERIFYING) {
          cleanupVerificationObjects(asset, client);
        }
      }
    } catch (RuntimeException exception) {
      metrics.verificationFailure();
    } finally {
      inFlight.remove(id);
      MDC.remove("traceId");
    }
  }

  /** 删除确认竞争中已经产生的 permanent/staging 对象，避免 CAS 失败留下孤立永久对象。 */
  private void cleanupVerificationObjects(FileAsset asset, ObjectStorageClient client) {
    deleteBestEffort(client, asset.getStorageKey());
    if (asset.getStagingStorageKey() != null
        && !asset.getStagingStorageKey().equals(asset.getStorageKey())) {
      deleteBestEffort(client, asset.getStagingStorageKey());
    }
  }

  /** 删除补偿只接受明确成功或不存在；未知结果交给后续删除恢复/人工核对。 */
  private void deleteBestEffort(ObjectStorageClient client, String key) {
    try {
      client.delete(key);
    } catch (ObjectStorageException exception) {
      if (exception.getCategory() != ObjectStorageException.Category.NOT_FOUND) {
        metrics.verificationFailure();
      }
    }
  }

  /** 完成状态不回滚；staging 删除失败由独立扫描和对象存储生命周期兜底。 */
  private void cleanupStaging(FileAsset asset, ObjectStorageClient client) {
    try {
      client.delete(asset.getStagingStorageKey());
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

  /**
   * @param owner 当前用户 ID @param id 文件 ID @return 非删除记录
   */
  private FileAsset requireVisible(String owner, String id) {
    return repository
        .findVisibleByIdAndOwner(id, owner)
        .orElseThrow(() -> new FileOperationException(HttpStatus.NOT_FOUND, "文件不存在"));
  }

  /** 为 V1 保留的独立读取器，隔离旧协议的全量摘要行为，确保 V2 主流程不会误调用 open。 */
  private static final class LegacyVerifier {
    /** 元数据仓储。 */
    private final FileAssetRepository repository;

    /** 存储适配器工厂。 */
    private final StorageFactory storageFactory;

    /** 文件参数。 */
    private final FileStorageProperties properties;

    /** UTC 时钟。 */
    private final Clock clock;

    /** 运行指标。 */
    private final FileOperationalMetrics metrics;

    /**
     * @param repository 元数据仓储
     * @param storageFactory 存储适配器工厂
     * @param properties 文件参数
     * @param clock UTC 时钟
     * @param metrics 运行指标
     */
    private LegacyVerifier(
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

    /** V1 服务端读取摘要；此方法不得被 V2 调用。 */
    private void verify(String owner, String id) {
      Instant deadline = Instant.now(clock).plus(properties.getConfirm().getTaskTimeout());
      FileAsset asset = repository.findVisibleByIdAndOwner(id, owner).orElse(null);
      if (asset == null
          || asset.getUploadStatus() != UploadStatus.PENDING
          || asset.getStatus() != AssetStatus.ACTIVE
          || asset.getUploadExpiresAt() == null
          || !asset.getUploadExpiresAt().isAfter(Instant.now(clock))) {
        if (asset != null) repository.expirePending(id, Instant.now(clock));
        return;
      }
      ObjectStorageClient client = storageFactory.require(asset.getStorageType());
      if (!hasBudget(deadline)) return;
      ObjectStorageClient.ObjectHead before = client.head(asset.getStorageKey());
      if (before.size() != asset.getDeclaredSize() || before.size() > properties.getMaxSize()) {
        // 已经确认内容不符合声明，立即作废；不使用自然到期 SQL，避免无效对象继续占用 PENDING。
        rejectInvalidContent(id);
        return;
      }
      long actual;
      String sha256;
      try (java.io.InputStream source = client.open(asset.getStorageKey());
          com.calles.platform.file.application.asset.BoundedDigestInputStream digest =
              new com.calles.platform.file.application.asset.BoundedDigestInputStream(
                  source, properties.getMaxSize())) {
        byte[] buffer = new byte[properties.getStreamBufferSize()];
        while (digest.read(buffer) != -1) {}
        actual = digest.count();
        sha256 = digest.sha256Hex();
      } catch (java.io.IOException exception) {
        metrics.operationFailure();
        return;
      }
      if (!hasBudget(deadline)) return;
      ObjectStorageClient.ObjectHead after = client.head(asset.getStorageKey());
      if (!sameObservedObject(before, after)
          || actual != before.size()
          || actual != asset.getDeclaredSize()) {
        // 第二次观测或实际读取已证明对象不稳定/不匹配，同样立即作废 V1 记录。
        rejectInvalidContent(id);
        return;
      }
      repository.complete(
          id,
          owner,
          asset.getDeclaredSize(),
          actual,
          sha256,
          asset.getStorageType(),
          asset.getStorageKey(),
          Instant.now(clock));
    }

    /**
     * 将已确认内容不合法的 V1 记录作废。返回 0 表示并发状态已经收敛，本任务不再尝试覆盖。
     *
     * @param id 文件 ID
     */
    private void rejectInvalidContent(String id) {
      int affected = repository.rejectPendingContent(id, Instant.now(clock));
      if (affected != 1) {
        // 可能已完成、自然到期、删除或被其他确认任务作废，保持数据库最终状态。
        return;
      }
    }

    private boolean hasBudget(Instant deadline) {
      return Instant.now(clock).isBefore(deadline);
    }

    private boolean sameObservedObject(
        ObjectStorageClient.ObjectHead first, ObjectStorageClient.ObjectHead second) {
      return first.size() == second.size()
          && first.etag() != null
          && first.etag().equals(second.etag())
          && first.lastModified() != null
          && first.lastModified().equals(second.lastModified());
    }
  }
}
