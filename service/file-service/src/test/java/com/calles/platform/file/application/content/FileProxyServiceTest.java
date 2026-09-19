package com.calles.platform.file.application.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.application.security.AssetTokenService;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.AssetStatus;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.StorageType;
import com.calles.platform.file.domain.asset.UploadStatus;
import com.calles.platform.file.exception.FileOperationException;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 轻量资源受控流代理服务单元测试。 */
class FileProxyServiceTest {

  private FileAssetRepository repository;
  private StorageFactory storageFactory;
  private ObjectStorageClient storageClient;
  private FileStorageProperties properties;
  private AssetTokenService tokenService;
  private FileProxyService proxyService;

  @BeforeEach
  void setUp() {
    repository = mock(FileAssetRepository.class);
    storageFactory = mock(StorageFactory.class);
    storageClient = mock(ObjectStorageClient.class);
    tokenService = mock(AssetTokenService.class);

    when(storageFactory.require(any())).thenReturn(storageClient);

    properties = new FileStorageProperties();
    properties.getProxy().setMaxSize(10L * 1024 * 1024); // 10MB
    properties.getProxy().setCacheMaxAge(Duration.ofHours(2));

    proxyService = new FileProxyService(repository, storageFactory, properties, tokenService);
  }

  @Test
  @DisplayName("命中 If-None-Match 强 ETag 时应直接返回 304，且绝不打开存储对象流")
  void shouldReturn304WhenEtagMatches() {
    String fileId = "test-image-01";
    String sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    FileAsset asset = buildAsset(fileId, 500_000, sha256, AssetStatus.ACTIVE, UploadStatus.COMPLETED);

    when(repository.findVisibleById(fileId)).thenReturn(Optional.of(asset));
    doNothing().when(tokenService).verify(anyString(), anyLong(), anyString());

    // 客户端携带带引号的 If-None-Match
    FileProxyService.ProxyResult result =
        proxyService.proxy(fileId, 9999999L, "mock-sign", "\"" + sha256 + "\"");

    assertTrue(result.isNotModified());
    assertEquals("\"" + sha256 + "\"", result.getEtag());
    assertNull(result.getInputStream());
    verify(storageClient, never()).open(anyString());
  }

  @Test
  @DisplayName("未命中 ETag 时应正常从存储打开输入流并封装 200 OK 代理结果")
  void shouldOpenStreamWhenEtagDoesNotMatch() {
    String fileId = "test-image-02";
    String sha256 = "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e";
    FileAsset asset = buildAsset(fileId, 1024, sha256, AssetStatus.ACTIVE, UploadStatus.COMPLETED);

    when(repository.findVisibleById(fileId)).thenReturn(Optional.of(asset));
    doNothing().when(tokenService).verify(anyString(), anyLong(), anyString());
    when(storageClient.open(asset.getStorageKey())).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

    FileProxyService.ProxyResult result =
        proxyService.proxy(fileId, 9999999L, "mock-sign", null);

    assertFalse(result.isNotModified());
    assertEquals("\"" + sha256 + "\"", result.getEtag());
    assertEquals(1024, result.getSize());
    assertEquals("image/png", result.getMime());
    assertNotNull(result.getInputStream());
    verify(storageClient).open(asset.getStorageKey());
  }

  @Test
  @DisplayName("资源尺寸超过 10MB 阈值应触发熔断并抛出 413 Payload Too Large")
  void shouldRejectWhenSizeExceedsLimit() {
    String fileId = "big-video-01";
    long largeSize = 15L * 1024 * 1024; // 15MB > 10MB
    FileAsset asset = buildAsset(fileId, largeSize, "dummy-sha256", AssetStatus.ACTIVE, UploadStatus.COMPLETED);

    when(repository.findVisibleById(fileId)).thenReturn(Optional.of(asset));
    doNothing().when(tokenService).verify(anyString(), anyLong(), anyString());

    FileOperationException ex =
        assertThrows(FileOperationException.class, () -> proxyService.proxy(fileId, 9999999L, "mock-sign", null));

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.getStatus());
    assertTrue(ex.getMessage().contains("超过代理上限"));
  }

  @Test
  @DisplayName("文件不存在或已被删除应抛出 404 Not Found")
  void shouldThrow404WhenFileNotFound() {
    String fileId = "non-existent-file";
    when(repository.findVisibleById(fileId)).thenReturn(Optional.empty());
    doNothing().when(tokenService).verify(anyString(), anyLong(), anyString());

    FileOperationException ex =
        assertThrows(FileOperationException.class, () -> proxyService.proxy(fileId, 9999999L, "mock-sign", null));

    assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
  }

  @Test
  @DisplayName("文件未处于 COMPLETED 状态应抛出 409 Conflict")
  void shouldThrow409WhenFileNotCompleted() {
    String fileId = "pending-file";
    FileAsset asset = buildAsset(fileId, 1000, "sha", AssetStatus.ACTIVE, UploadStatus.PENDING);

    when(repository.findVisibleById(fileId)).thenReturn(Optional.of(asset));
    doNothing().when(tokenService).verify(anyString(), anyLong(), anyString());

    FileOperationException ex =
        assertThrows(FileOperationException.class, () -> proxyService.proxy(fileId, 9999999L, "mock-sign", null));

    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
  }

  private FileAsset buildAsset(String id, long size, String sha256, AssetStatus status, UploadStatus uploadStatus) {
    FileAsset asset = new FileAsset();
    asset.setId(id);
    asset.setOriginName("test.png");
    asset.setMime("image/png");
    asset.setSize(size);
    asset.setDeclaredSize(size);
    asset.setSha256(sha256);
    asset.setStatus(status);
    asset.setUploadStatus(uploadStatus);
    asset.setStorageType(StorageType.ALIYUN_OSS);
    asset.setStorageKey("permanent/2026/09/14/" + id);
    return asset;
  }
}
