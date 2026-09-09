package com.calles.platform.file.application.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.infrastructure.observability.FileOperationalMetrics;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import com.calles.platform.file.interfaces.http.dto.DirectUploadRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/** 文件上传用例中对象 key 生成和元数据持久化的单元测试。 */
class FileAssetApplicationServiceTest {
  /** 固定 UTC 时钟，确保日期分层规则不受执行机器时区影响。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

  /** 对象 PUT 明确成功且完整消费流后，服务应保存包含 UTC 日期前缀的 COMPLETED 元数据。 */
  @Test
  void uploadsThenPersistsCompletedMetadataWithDatePartitionedKey() throws Exception {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    when(storageFactory.require(any())).thenReturn(storage);
    when(repository.insert(any())).thenReturn(1);
    doAnswer(
            invocation -> {
              InputStream input = invocation.getArgument(2);
              while (input.read() != -1) {
                /* 模拟 SDK 流式消费。 */
              }
              return null;
            })
        .when(storage)
        .put(any(), any(), any(), any(Long.class));
    FileAssetApplicationService service = newService(repository, storageFactory);

    var result =
        service.upload(
            "u1",
            new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes()),
            null);

    ArgumentCaptor<FileAsset> assetCaptor = ArgumentCaptor.forClass(FileAsset.class);
    verify(repository).insert(assetCaptor.capture());
    assertEquals("permanent/2026/09/08/" + result.fileId(), assetCaptor.getValue().getStorageKey());
    assertEquals("hello.txt", result.originName());
    assertEquals(5L, result.declaredSize());
    assertEquals(5L, result.actualSize());
    assertEquals("COMPLETED", result.uploadStatus());
  }

  /** 预签名直传应将同一 UTC 日期分层 key 持久化，并用该 key 生成 PUT 签名。 */
  @Test
  void initializesDirectUploadWithDatePartitionedKey() {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    when(storageFactory.require(any())).thenReturn(storage);
    when(repository.insert(any())).thenReturn(1);
    when(storage.presignPut(any(), any(), any()))
        .thenReturn(
            new ObjectStorageClient.PresignedUrl(
                "https://storage.example.test/upload",
                Map.of("Content-Type", "text/plain"),
                CLOCK.instant()));
    FileAssetApplicationService service = newService(repository, storageFactory);

    var result =
        service.initializeDirectUpload(
            "u1", new DirectUploadRequest("draft.txt", 5L, "text/plain", null));

    ArgumentCaptor<FileAsset> assetCaptor = ArgumentCaptor.forClass(FileAsset.class);
    verify(repository).insert(assetCaptor.capture());
    assertEquals("assets/2026/09/08/" + result.fileId(), assetCaptor.getValue().getStorageKey());
    verify(storage)
        .presignPut(assetCaptor.getValue().getStorageKey(), "text/plain", configured().getPutTtl());
  }

  /**
   * @param repository 文件元数据仓储 mock
   * @param storageFactory 对象存储工厂 mock
   * @return 注入固定 UTC Clock 的待测服务
   */
  private FileAssetApplicationService newService(
      FileAssetRepository repository, StorageFactory storageFactory) {
    return new FileAssetApplicationService(
        repository,
        storageFactory,
        configured(),
        CLOCK,
        new FileOperationalMetrics(new SimpleMeterRegistry()));
  }

  /**
   * @return 可用于应用单元测试的非敏感配置
   */
  private FileStorageProperties configured() {
    FileStorageProperties properties = new FileStorageProperties();
    properties.getMinio().setEndpoint("http://minio.internal:9000");
    properties.getMinio().setPresignEndpoint("http://minio.local:9000");
    properties.getMinio().setBucket("file-test");
    properties.getMinio().setAccessKey("test-access");
    properties.getMinio().setSecretKey("test-secret");
    return properties;
  }
}
