package com.calles.platform.file.application.cleanup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.AssetStatus;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.StorageType;
import com.calles.platform.file.domain.asset.UploadStatus;
import com.calles.platform.file.infrastructure.observability.FileOperationalMetrics;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 删除恢复任务的应用层测试，覆盖远端已成功但最终墓碑未落库后的收敛路径。 */
class FileDeletionRecoveryTest {
  /** 固定时钟便于断言恢复任务携带的时间不受机器时区影响。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

  /** 删除恢复应独立于 EXPIRED 清理，远端成功后补写逻辑删除墓碑。 */
  @Test
  void recoversDeletionRequestedRecordToTombstone() {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    FileAsset deleting = deletingAsset();
    when(repository.findDeletionRequested(any(), any(), eq(10)))
        .thenReturn(List.of(deleting))
        .thenReturn(List.of());
    when(storageFactory.require(StorageType.MINIO)).thenReturn(storage);
    when(repository.completeDeletion(
            eq("f1"), eq("u1"), eq(StorageType.MINIO), eq("assets/f1"), any()))
        .thenReturn(1);
    FileCleanupService service =
        new FileCleanupService(
            repository,
            storageFactory,
            new FileStorageProperties(),
            CLOCK,
            new FileOperationalMetrics(new SimpleMeterRegistry()));

    service.recoverDeletion(10, 2, Duration.ofSeconds(1));

    verify(storage).delete("assets/f1");
    verify(repository)
        .completeDeletion(eq("f1"), eq("u1"), eq(StorageType.MINIO), eq("assets/f1"), any());
  }

  /** @return 已建立删除闸门、尚未写逻辑墓碑的固定元数据 */
  private FileAsset deletingAsset() {
    return new FileAsset(
        "f1",
        "demo.txt",
        "text/plain",
        5L,
        null,
        "assets/f1",
        StorageType.MINIO,
        null,
        AssetStatus.ACTIVE,
        UploadStatus.PENDING,
        CLOCK.instant().plusSeconds(60),
        "u1",
        "u1",
        CLOCK.instant(),
        CLOCK.instant(),
        CLOCK.instant(),
        null);
  }
}
