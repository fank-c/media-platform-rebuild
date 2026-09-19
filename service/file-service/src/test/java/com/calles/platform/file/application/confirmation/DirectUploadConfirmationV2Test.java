package com.calles.platform.file.application.confirmation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** V2 确认测试，证明确认只走 HEAD/copy/CAS，不打开 staging 全量流。 */
class DirectUploadConfirmationV2Test {
  /** 固定时间避免测试受执行机器时区和当前时间影响。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);

  /** PENDING 只能抢占一次，完成过程不调用 ObjectStorageClient.open。 */
  @Test
  void confirmsByHeadAndConditionalCopyWithoutReadingObject() throws Exception {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    FileAsset asset = verifyingAsset();
    CountDownLatch completed = new CountDownLatch(1);
    when(repository.findVisibleByIdAndOwner("f1", "u1")).thenReturn(Optional.of(asset));
    when(repository.claimVerification("f1", "u1", CLOCK.instant()))
        .thenAnswer(
            invocation -> {
              asset.setUploadStatus(UploadStatus.VERIFYING);
              asset.setVerificationRequestedAt(CLOCK.instant());
              return 1;
            });
    when(repository.observeVerification(eq("f1"), eq("u1"), eq("etag-1"), any())).thenReturn(1);
    when(repository.completeVerification(
            eq("f1"),
            eq("u1"),
            eq(5L),
            eq(5L),
            eq("a".repeat(64)),
            eq("etag-1"),
            eq(StorageType.ALIYUN_OSS),
            eq("permanent/2026/09/09/f1"),
            eq("staging/2026/09/09/f1"),
            any()))
        .thenAnswer(
            invocation -> {
              completed.countDown();
              return 1;
            });
    when(repository.markStagingCleaned(any(), any(), any())).thenReturn(1);
    when(storageFactory.require(StorageType.ALIYUN_OSS)).thenReturn(storage);
    when(storage.head("staging/2026/09/09/f1"))
        .thenReturn(
            new ObjectStorageClient.ObjectHead(5L, "etag-1", CLOCK.instant(), "text/plain"));
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    try {
      DirectUploadConfirmationService service =
          new DirectUploadConfirmationService(
              repository,
              storageFactory,
              executor,
              configured(),
              CLOCK,
              new FileOperationalMetrics(new SimpleMeterRegistry()));

      Object result = service.confirmV2("u1", "f1");

      assertEquals(
          UploadStatus.VERIFYING.name(),
          ((com.calles.platform.file.interfaces.http.dto.FileResponses.ConfirmationAccepted) result)
              .uploadStatus());
      assertTrue(completed.await(2, TimeUnit.SECONDS));
      verify(storage).head("staging/2026/09/09/f1");
      verify(storage).copy("staging/2026/09/09/f1", "permanent/2026/09/09/f1", "etag-1");
      verify(storage, never()).open(any());
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * @return 已进入 V2 确认流程的固定记录
   */
  private FileAsset verifyingAsset() {
    FileAsset asset =
        new FileAsset(
            "f1",
            "demo.txt",
            "text/plain",
            5L,
            null,
            "permanent/2026/09/09/f1",
            StorageType.ALIYUN_OSS,
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
    asset.setUploadProtocol(UploadProtocol.DIRECT_STAGED_CHECKSUM_V2);
    asset.setStagingStorageKey("staging/2026/09/09/f1");
    asset.setExpectedSha256("a".repeat(64));
    return asset;
  }

  /**
   * @return V2 测试用完整非敏感配置
   */
  private FileStorageProperties configured() {
    FileStorageProperties properties = new FileStorageProperties();
    properties.getOss().setEndpoint("https://oss-cn-beijing.aliyuncs.com");
    properties.getOss().setPresignEndpoint("https://oss-cn-beijing.aliyuncs.com");
    properties.getOss().setRegion("cn-beijing");
    properties.getOss().setBucket("file-test");
    properties.getOss().setAccessKey("test-access");
    properties.getOss().setSecretKey("test-secret");
    return properties;
  }
}
