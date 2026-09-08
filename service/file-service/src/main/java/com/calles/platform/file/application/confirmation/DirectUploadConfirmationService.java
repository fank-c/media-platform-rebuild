package com.calles.platform.file.application.confirmation;

import com.calles.platform.file.application.asset.BoundedDigestInputStream;
import com.calles.platform.file.application.asset.FileAssetViews;
import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.AssetStatus;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.UploadStatus;
import com.calles.platform.file.exception.FileOperationException;
import com.calles.platform.file.exception.ObjectStorageException;
import com.calles.platform.file.infrastructure.observability.FileOperationalMetrics;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import com.calles.platform.file.interfaces.http.dto.FileResponses;
import java.io.InputStream;
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
 * 预签名 PUT 的有界异步服务端确认。
 *
 * <p>进程内集合只消除同实例重复排队；跨实例仍允许重复读取，最终状态以数据库 CAS 为准。
 */
@Service
public class DirectUploadConfirmationService {
  /** 元数据仓储。 */
  private final FileAssetRepository repository;

  /** 存储适配器工厂。 */
  private final StorageFactory storageFactory;

  /** 专用有界摘要线程池。 */
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
   * 同步校验所属、状态和期限，再把实际对象读取交给有界工作池。
   *
   * @param owner 当前用户 ID
   * @param id 文件 ID
   * @return 已完成元数据，或真实 202 的异步状态
   */
  public Object confirm(String owner, String id) {
    FileAsset asset = requireVisible(owner, id);
    if (asset.getUploadStatus() == UploadStatus.COMPLETED) return FileAssetViews.metadata(asset);
    Instant now = Instant.now(clock);
    if (asset.getUploadStatus() == UploadStatus.EXPIRED
        || asset.getUploadExpiresAt() == null
        || !asset.getUploadExpiresAt().isAfter(now)) {
      repository.expirePending(id, now);
      throw new FileOperationException(HttpStatus.GONE, "上传确认已过期");
    }
    if (!inFlight.add(id)) return accepted(id);
    String traceId = MDC.get("traceId");
    try {
      // 拒绝时立即移除标记，保留 PENDING 供客户端在有余量后重新确认。
      executor.execute(() -> verify(owner, id, traceId));
      return accepted(id);
    } catch (RejectedExecutionException exception) {
      inFlight.remove(id);
      metrics.confirmationRejected();
      throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "文件确认队列繁忙，请稍后重试", exception);
    }
  }

  /**
   * @param id 文件 ID
   * @return 建议客户端轮询的 202 状态
   */
  private FileResponses.ConfirmationAccepted accepted(String id) {
    return new FileResponses.ConfirmationAccepted(id, UploadStatus.PENDING.name(), 2L);
  }

  /**
   * 在后台重新读取元数据、执行 A/GET/B 有限观测并使用完成 CAS。
   *
   * @param owner 提交确认时捕获的主体，而非请求 ThreadLocal
   * @param id 文件 ID
   * @param traceId 捕获的追踪标识，任务结束时清理
   */
  private void verify(String owner, String id, String traceId) {
    if (traceId != null) MDC.put("traceId", traceId);
    try {
      // Future 取消不能保证底层 SDK I/O 已关闭；在每个可控步骤检查固定预算，避免完成 CAS 越过任务期限。
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
        repository.expirePending(id, Instant.now(clock));
        return;
      }
      long actual;
      String sha256;
      // 读取对象时只使用固定缓冲区，finally 确保 SDK 响应在任意失败出口释放。
      try (InputStream source = client.open(asset.getStorageKey());
          BoundedDigestInputStream digest =
              new BoundedDigestInputStream(source, properties.getMaxSize())) {
        byte[] buffer = new byte[properties.getStreamBufferSize()];
        while (digest.read(buffer) != -1) {
          /* 摘要和实际长度由流包装器累计。 */
        }
        actual = digest.count();
        sha256 = digest.sha256Hex();
      }
      if (!hasBudget(deadline)) return;
      ObjectStorageClient.ObjectHead after = client.head(asset.getStorageKey());
      if (!hasBudget(deadline)
          || !sameObservedObject(before, after)
          || actual != before.size()
          || actual != asset.getDeclaredSize()) {
        repository.expirePending(id, Instant.now(clock));
        return;
      }
      // 长读取结束后重新取 now，确保已经到期、删除或被清理的记录不能被重新完成。
      repository.complete(
          id,
          owner,
          asset.getDeclaredSize(),
          actual,
          sha256,
          asset.getStorageType(),
          asset.getStorageKey(),
          Instant.now(clock));
    } catch (java.io.IOException exception) {
      // 读取或关闭流失败时不能写 COMPLETED，记录后保留 PENDING 供有效期内重试。
      metrics.operationFailure();
    } catch (ObjectStorageException exception) {
      // 明确不存在或远端技术故障都保留 PENDING；客户端可在有效期内重提确认。
      metrics.operationFailure();
    } catch (RuntimeException exception) {
      metrics.operationFailure();
    } finally {
      inFlight.remove(id);
      MDC.remove("traceId");
    }
  }

  /**
   * @param deadline 固定任务截止时间
   * @return 当前时间仍严格早于截止时间
   */
  private boolean hasBudget(Instant deadline) {
    return Instant.now(clock).isBefore(deadline);
  }

  /** 比较 A/B 有限观测，字段缺失时失败关闭而不把仅大小相等说成内容稳定。 */
  private boolean sameObservedObject(
      ObjectStorageClient.ObjectHead first, ObjectStorageClient.ObjectHead second) {
    return first.size() == second.size()
        && first.etag() != null
        && first.etag().equals(second.etag())
        && first.lastModified() != null
        && first.lastModified().equals(second.lastModified());
  }

  /** 隐藏未知、他人和已删除记录。 */
  private FileAsset requireVisible(String owner, String id) {
    return repository
        .findVisibleByIdAndOwner(id, owner)
        .orElseThrow(() -> new FileOperationException(HttpStatus.NOT_FOUND, "文件不存在"));
  }
}
