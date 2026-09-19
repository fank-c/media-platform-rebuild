package com.calles.platform.file.application.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.UploadProtocol;
import com.calles.platform.file.infrastructure.observability.FileOperationalMetrics;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import com.calles.platform.file.interfaces.http.dto.DirectUploadV2Request;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** V2 直传初始化测试，确保摘要只进入 expected_sha256 且 PUT 只指向 staging。 */
class FileAssetV2UploadTest {
  /** 固定 UTC 时间，验证日期路径不会受执行机器时区影响。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);

  /** 初始化应生成 staging/permanent 两个服务端 key，并签发 checksum 请求头。 */
  @Test
  void initializesStagedChecksumUpload() {
    FileAssetRepository repository = mock(FileAssetRepository.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    ObjectStorageClient storage = mock(ObjectStorageClient.class);
    when(repository.insert(any())).thenReturn(1);
    when(storageFactory.require(any())).thenReturn(storage);
    when(storage.presignPut(any(), any(), any(String.class), any()))
        .thenReturn(
            new ObjectStorageClient.PresignedUrl(
                "https://storage.example.test/upload",
                Map.of(
                    "Content-Type",
                    "application/octet-stream",
                    "x-amz-checksum-sha256",
                    "checksum"),
                CLOCK.instant().plusSeconds(300)));
    FileStorageProperties properties = configured();
    properties.getDirectUploadV2().setEnabled(true);
    properties.getCleanup().setEnabled(true);
    FileAssetApplicationService service =
        new FileAssetApplicationService(
            repository,
            storageFactory,
            properties,
            CLOCK,
            new FileOperationalMetrics(new SimpleMeterRegistry()));

    String sha256 = "a".repeat(64);
    var response =
        service.initializeDirectUploadV2(
            "u1", new DirectUploadV2Request("demo.txt", 5L, null, null, sha256));

    ArgumentCaptor<com.calles.platform.file.domain.asset.FileAsset> captor =
        ArgumentCaptor.forClass(com.calles.platform.file.domain.asset.FileAsset.class);
    verify(repository).insert(captor.capture());
    var asset = captor.getValue();
    assertEquals(UploadProtocol.DIRECT_STAGED_CHECKSUM_V2, asset.getUploadProtocol());
    assertEquals(sha256, asset.getExpectedSha256());
    assertNull(asset.getSha256());
    assertEquals("staging/2026/09/09/" + response.fileId(), asset.getStagingStorageKey());
    assertEquals("permanent/2026/09/09/" + response.fileId(), asset.getStorageKey());
    ArgumentCaptor<String> checksumCaptor = ArgumentCaptor.forClass(String.class);
    verify(storage).presignPut(any(), any(), checksumCaptor.capture(), any());
    assertEquals(Base64.getEncoder().encodeToString(hexBytes(sha256)), checksumCaptor.getValue());
  }

  /** 将测试摘要转换为对象存储 checksum header。 */
  private byte[] hexBytes(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return bytes;
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
