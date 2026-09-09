package com.calles.platform.file.application.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import com.calles.platform.file.domain.asset.UploadStatus;
import com.calles.platform.file.exception.FileOperationException;
import com.calles.platform.file.exception.ObjectStorageException;
import com.calles.platform.file.infrastructure.observability.FileOperationalMetrics;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 删除闸门顺序、失败关闭和重复 DELETE 语义的应用层测试。 */
class FileAssetDeletionTest {
  /** 固定时钟使删除闸门和墓碑时间可预测。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

  /** 成功路径必须先建立数据库闸门，再调用远端删除，最后条件写入墓碑。 */
  @Test
  void establishesDeletionGateBeforeRemoteDeletionAndTombstone() {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    FileAsset asset = activeAsset(null, null);
    when(repository.findPhysicalByIdAndOwner("f1", "u1")).thenReturn(Optional.of(asset));
    when(repository.requestDeletion(eq("f1"), eq("u1"), any())).thenReturn(1);
    when(storageFactory.require(StorageType.MINIO)).thenReturn(storage);
    when(repository.completeDeletion(eq("f1"), eq("u1"), eq(StorageType.MINIO), eq("assets/f1"), any()))
        .thenReturn(1);

    newService(repository, storageFactory).delete("u1", "f1");

    InOrder order = inOrder(repository, storage);
    order.verify(repository).requestDeletion(eq("f1"), eq("u1"), any());
    order.verify(storage).delete("assets/f1");
    order.verify(repository)
        .completeDeletion(eq("f1"), eq("u1"), eq(StorageType.MINIO), eq("assets/f1"), any());
  }

  /** 远端结果未知时保留已建立的删除闸门，向客户端明确返回可重试的 503。 */
  @Test
  void keepsDeletionGateWhenRemoteDeletionResultIsUnknown() {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    FileAsset asset = activeAsset(null, null);
    when(repository.findPhysicalByIdAndOwner("f1", "u1")).thenReturn(Optional.of(asset));
    when(repository.requestDeletion(eq("f1"), eq("u1"), any())).thenReturn(1);
    when(storageFactory.require(StorageType.MINIO)).thenReturn(storage);
    ObjectStorageException failure =
        new ObjectStorageException(
            ObjectStorageException.Category.UNKNOWN_RESULT, "delete result unknown", null);
    org.mockito.Mockito.doThrow(failure).when(storage).delete("assets/f1");

    FileOperationException exception =
        assertThrows(FileOperationException.class, () -> newService(repository, storageFactory).delete("u1", "f1"));

    assertEquals(503, exception.getStatus().value());
    verify(repository, never())
        .completeDeletion(eq("f1"), eq("u1"), eq(StorageType.MINIO), eq("assets/f1"), any());
  }

  /** 远端已删但最终墓碑更新仍未确认时，记录不能通过本次请求重新变为可见。 */
  @Test
  void returnsServiceUnavailableWhenTombstoneWriteIsNotConfirmed() {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    FileAsset asset = activeAsset(null, null);
    FileAsset stillDeleting = activeAsset(CLOCK.instant(), null);
    when(repository.findPhysicalByIdAndOwner("f1", "u1"))
        .thenReturn(Optional.of(asset))
        .thenReturn(Optional.of(stillDeleting));
    when(repository.requestDeletion(eq("f1"), eq("u1"), any())).thenReturn(1);
    when(storageFactory.require(StorageType.MINIO)).thenReturn(storage);
    when(repository.completeDeletion(eq("f1"), eq("u1"), eq(StorageType.MINIO), eq("assets/f1"), any()))
        .thenReturn(0);

    FileOperationException exception =
        assertThrows(FileOperationException.class, () -> newService(repository, storageFactory).delete("u1", "f1"));

    assertEquals(503, exception.getStatus().value());
    verify(storage).delete("assets/f1");
  }

  /** 已存在删除墓碑的重复 DELETE 不再访问远端对象，保持 HTTP 204 幂等语义。 */
  @Test
  void returnsNormallyForExistingTombstone() {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    when(repository.findPhysicalByIdAndOwner("f1", "u1"))
        .thenReturn(Optional.of(activeAsset(CLOCK.instant(), CLOCK.instant())));

    newService(repository, storageFactory).delete("u1", "f1");

    verify(repository, never()).requestDeletion(eq("f1"), eq("u1"), any());
    verify(storageFactory, never()).require(any(StorageType.class));
  }

  /**
   * 构造可用于删除测试的文件记录。
   *
   * @param deleteRequestedAt 删除闸门时间，可为空
   * @param deletedAt 逻辑墓碑时间，可为空
   * @return 固定对象定位的文件元数据
   */
  private FileAsset activeAsset(Instant deleteRequestedAt, Instant deletedAt) {
    return new FileAsset(
        "f1",
        "demo.txt",
        "text/plain",
        5L,
        5L,
        "assets/f1",
        StorageType.MINIO,
        "0".repeat(64),
        AssetStatus.ACTIVE,
        UploadStatus.COMPLETED,
        null,
        "u1",
        "u1",
        CLOCK.instant(),
        CLOCK.instant(),
        deleteRequestedAt,
        deletedAt);
  }

  /**
   * @param repository 元数据仓储 mock
   * @param storageFactory 对象存储工厂 mock
   * @return 注入固定时钟的删除用例
   */
  private FileAssetApplicationService newService(
      FileAssetRepository repository, StorageFactory storageFactory) {
    FileStorageProperties properties = new FileStorageProperties();
    return new FileAssetApplicationService(
        repository,
        storageFactory,
        properties,
        CLOCK,
        new FileOperationalMetrics(new SimpleMeterRegistry()));
  }
}
