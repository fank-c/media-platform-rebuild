package com.calles.platform.file.application.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.exception.FileOperationException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 静态资源时效签名与防盗链令牌服务单元测试。 */
class AssetTokenServiceTest {

  private FileStorageProperties properties;
  private AssetTokenService tokenService;

  @BeforeEach
  void setUp() {
    properties = new FileStorageProperties();
    properties.getSecurity().setTokenSecret("test_secure_secret_key_123456");
    properties.getSecurity().setDefaultTtl(Duration.ofMinutes(15));
    tokenService = new AssetTokenService(properties);
  }

  @Test
  @DisplayName("生成并校验合法时效签名应正常通过")
  void shouldGenerateAndVerifyValidToken() {
    String fileId = "file-abc-123";
    long expires = System.currentTimeMillis() + 60_000;
    String sign = tokenService.calculateSignature(fileId, expires);

    assertDoesNotThrow(() -> tokenService.verify(fileId, expires, sign));
  }

  @Test
  @DisplayName("生成包含参数的访问相对 URL 应符合结构规范")
  void shouldGenerateValidAssetUrl() {
    String fileId = "test-avatar-888";
    String url = tokenService.generateAssetUrl(fileId, Duration.ofMinutes(10));

    assertNotNull(url);
    assertTrue(url.startsWith("/api/files/assets/" + fileId + "?expires="));
    assertTrue(url.contains("&sign="));
  }

  @Test
  @DisplayName("签名过期应抛出 403 业务异常")
  void shouldRejectExpiredToken() {
    String fileId = "file-abc-123";
    long expiredTime = System.currentTimeMillis() - 1000;
    String sign = tokenService.calculateSignature(fileId, expiredTime);

    FileOperationException ex =
        assertThrows(FileOperationException.class, () -> tokenService.verify(fileId, expiredTime, sign));
    assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    assertTrue(ex.getMessage().contains("已过期"));
  }

  @Test
  @DisplayName("篡改签名串应抛出 403 业务异常")
  void shouldRejectTamperedSignature() {
    String fileId = "file-abc-123";
    long expires = System.currentTimeMillis() + 60_000;
    String tamperedSign = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    FileOperationException ex =
        assertThrows(FileOperationException.class, () -> tokenService.verify(fileId, expires, tamperedSign));
    assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    assertTrue(ex.getMessage().contains("非法资源访问凭证"));
  }

  @Test
  @DisplayName("篡改 fileId 应导致签名校验失败并抛出 403 业务异常")
  void shouldRejectTamperedFileId() {
    String originalFileId = "file-legit-001";
    String stolenFileId = "file-stolen-002";
    long expires = System.currentTimeMillis() + 60_000;
    String sign = tokenService.calculateSignature(originalFileId, expires);

    FileOperationException ex =
        assertThrows(FileOperationException.class, () -> tokenService.verify(stolenFileId, expires, sign));
    assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
  }

  @Test
  @DisplayName("缺失关键参数应抛出 403 业务异常")
  void shouldRejectMissingParameters() {
    assertThrows(FileOperationException.class, () -> tokenService.verify(null, 1000L, "sign"));
    assertThrows(FileOperationException.class, () -> tokenService.verify("id", 1000L, ""));
  }
}
