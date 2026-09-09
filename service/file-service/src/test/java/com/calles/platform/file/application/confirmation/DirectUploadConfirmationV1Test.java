package com.calles.platform.file.application.confirmation;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.AssetStatus;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.StorageType;
import com.calles.platform.file.domain.asset.UploadProtocol;
import com.calles.platform.file.domain.asset.UploadStatus;
import com.calles.platform.file.infrastructure.observability.FileOperationalMetrics;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** V1 确认测试，区分内容作废与技术失败两类结果。 */
class DirectUploadConfirmationV1Test {
  /** 固定时间，确保测试只验证状态条件而不依赖机器时钟。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);

  /** 截止时间未到但首次 HEAD 大小错误时，必须立即作废而不是调用自然到期 SQL。 */
  @Test
  void rejectsContentSizeMismatchBeforeUploadDeadline() throws Exception {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    CountDownLatch rejected = new CountDownLatch(1);
    FileAsset asset = legacyAsset(10L);
    when(repository.findVisibleByIdAndOwner("f1", "u1")).thenReturn(Optional.of(asset));
    when(repository.rejectPendingContent(eq("f1"), any()))
        .thenAnswer(
            invocation -> {
              rejected.countDown();
              return 1;
            });
    when(storageFactory.require(StorageType.MINIO)).thenReturn(storage);
    when(storage.head("assets/2026/09/09/f1"))
        .thenReturn(new ObjectStorageClient.ObjectHead(5L, "etag-1", CLOCK.instant(), "text/plain"));

    ThreadPoolExecutor executor = executor();
    try {
      DirectUploadConfirmationService service =
          new DirectUploadConfirmationService(
              repository,
              storageFactory,
              executor,
              configured(),
              CLOCK,
              new FileOperationalMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

      service.confirm("u1", "f1");

      assertTrue(rejected.await(2, TimeUnit.SECONDS));
      verify(repository).rejectPendingContent(eq("f1"), any());
      verify(repository, never()).expirePending(any(), any());
      verify(storage, never()).open(any());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 截止时间未到但两次对象观测不一致时，必须立即作废 V1 记录。 */
  @Test
  void rejectsReplacedObjectAfterStreamingRead() throws Exception {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    CountDownLatch rejected = new CountDownLatch(1);
    FileAsset asset = legacyAsset(10L);
    when(repository.findVisibleByIdAndOwner("f1", "u1")).thenReturn(Optional.of(asset));
    when(repository.rejectPendingContent(eq("f1"), any()))
        .thenAnswer(
            invocation -> {
              rejected.countDown();
              return 1;
            });
    when(storageFactory.require(StorageType.MINIO)).thenReturn(storage);
    when(storage.head("assets/2026/09/09/f1"))
        .thenReturn(
            new ObjectStorageClient.ObjectHead(10L, "etag-before", CLOCK.instant(), "text/plain"),
            new ObjectStorageClient.ObjectHead(
                10L, "etag-after", CLOCK.instant(), "text/plain"));
    when(storage.open("assets/2026/09/09/f1"))
        .thenReturn(new ByteArrayInputStream("0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    ThreadPoolExecutor executor = executor();
    try {
      DirectUploadConfirmationService service =
          new DirectUploadConfirmationService(
              repository,
              storageFactory,
              executor,
              configured(),
              CLOCK,
              new FileOperationalMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

      service.confirm("u1", "f1");

      assertTrue(rejected.await(2, TimeUnit.SECONDS));
      verify(repository).rejectPendingContent(eq("f1"), any());
      verify(repository, never()).expirePending(any(), any());
      verify(repository, never()).complete(any(), any(), any(Long.class), any(Long.class), any(), any(), any(), any());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 对象读取发生 I/O 异常时保留 PENDING，允许后续确认重试。 */
  @Test
  void keepsPendingWhenObjectReadFailsTechnically() throws Exception {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    FileAsset asset = legacyAsset(10L);
    when(repository.findVisibleByIdAndOwner("f1", "u1")).thenReturn(Optional.of(asset));
    when(storageFactory.require(StorageType.MINIO)).thenReturn(storage);
    when(storage.head("assets/2026/09/09/f1"))
        .thenReturn(new ObjectStorageClient.ObjectHead(10L, "etag-1", CLOCK.instant(), "text/plain"));
    when(storage.open("assets/2026/09/09/f1")).thenReturn(new FailingInputStream());

    ThreadPoolExecutor executor = executor();
    try {
      DirectUploadConfirmationService service =
          new DirectUploadConfirmationService(
              repository,
              storageFactory,
              executor,
              configured(),
              CLOCK,
              new FileOperationalMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

      service.confirm("u1", "f1");

      executor.shutdown();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
      verify(repository, never()).rejectPendingContent(any(), any());
      verify(repository, never()).expirePending(any(), any());
      verify(repository, never()).complete(any(), any(), any(Long.class), any(Long.class), any(), any(), any(), any());
    } finally {
      executor.shutdownNow();
    }
  }

  /** @param declaredSize 客户端声明的文件大小 @return V1 PENDING 测试记录 */
  private FileAsset legacyAsset(long declaredSize) {
    FileAsset asset =
        new FileAsset(
            "f1",
            "demo.txt",
            "text/plain",
            declaredSize,
            null,
            "assets/2026/09/09/f1",
            StorageType.MINIO,
            null,
            AssetStatus.ACTIVE,
            UploadStatus.PENDING,
            CLOCK.instant().plusSeconds(60),
            "u1",
            "u1",
            CLOCK.instant(),
            CLOCK.instant(),
            null,
            null);
    asset.setUploadProtocol(UploadProtocol.LEGACY_V1);
    return asset;
  }

  /** @return 单线程有界执行器，模拟生产确认队列并等待后台任务结束 */
  private ThreadPoolExecutor executor() {
    return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
  }

  /** @return V1 测试所需的非敏感对象存储配置 */
  private FileStorageProperties configured() {
    FileStorageProperties properties = new FileStorageProperties();
    properties.getMinio().setEndpoint("http://minio.internal:9000");
    properties.getMinio().setPresignEndpoint("http://minio.local:9000");
    properties.getMinio().setBucket("file-test");
    properties.getMinio().setAccessKey("test-access");
    properties.getMinio().setSecretKey("test-secret");
    return properties;
  }

  /** 只为验证技术失败出口而抛出 I/O 异常的输入流。 */
  private static final class FailingInputStream extends InputStream {
    /** @return 永不返回数据，直接模拟对象存储读取失败 */
    @Override
    public int read() throws IOException {
      throw new IOException("simulated storage read failure");
    }
  }
}
