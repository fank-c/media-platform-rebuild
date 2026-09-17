package com.calles.platform.file.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.file.application.asset.FileAssetApplicationService;
import com.calles.platform.file.application.confirmation.DirectUploadConfirmationService;
import com.calles.platform.file.application.content.FileProxyService;
import com.calles.platform.file.application.security.AssetTokenService;
import com.calles.platform.file.application.security.FileAccessPolicy;
import com.calles.platform.file.exception.FileOperationException;
import com.calles.platform.file.interfaces.http.dto.FileResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内部文件上传接口 (FileInternalControllerTest) 单元测试。
 *
 * <p>覆盖正常系统级托管上传及非法作者校验。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileInternalControllerTest {

  @Mock private FileAccessPolicy accessPolicy;
  @Mock private FileAssetApplicationService fileService;
  @Mock private DirectUploadConfirmationService confirmationService;
  @Mock private FileProxyService fileProxyService;
  @Mock private AssetTokenService tokenService;

  private FileController controller;

  @BeforeEach
  void setUp() {
    controller =
        new FileController(
            accessPolicy, fileService, confirmationService, fileProxyService, tokenService);
  }

  @Test
  @DisplayName("内部上传成功：正常接收文件流与 authorId 并返回 201 资产元数据")
  void uploadInternal_success() {
    // 步骤 1：准备测试文件与返回元数据
    MockMultipartFile multipartFile =
        new MockMultipartFile("file", "720p.mp4", "video/mp4", "mock-video-content".getBytes());
    FileResponses.Metadata expectedMetadata =
        new FileResponses.Metadata(
            "file_123",
            "720p.mp4",
            "video/mp4",
            2048L,
            2048L,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "ACTIVE",
            "COMPLETED",
            null,
            Instant.now(),
            Instant.now());

    when(fileService.upload(eq("author_123"), eq(multipartFile), eq(null)))
        .thenReturn(expectedMetadata);

    // 步骤 2：发起调用
    ResponseEntity<ApiResponse<FileResponses.Metadata>> response =
        controller.uploadInternal(multipartFile, "author_123", null);

    // 步骤 3：断言结果
    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(200, response.getBody().code());
    assertEquals("file_123", response.getBody().data().fileId());
    verify(fileService).upload("author_123", multipartFile, null);
  }

  @Test
  @DisplayName("内部上传失败：authorId 为空时抛出 400 BAD_REQUEST 异常")
  void uploadInternal_blankAuthorId_throwsException() {
    MockMultipartFile multipartFile =
        new MockMultipartFile("file", "test.mp4", "video/mp4", "content".getBytes());

    FileOperationException exception =
        assertThrows(
            FileOperationException.class,
            () -> controller.uploadInternal(multipartFile, "   ", null));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    assertEquals("authorId 不能为空", exception.getMessage());
  }

  @Test
  @DisplayName("内部获取下载URL成功：无需用户Token，直接返回预签名下载地址")
  void downloadUrlInternal_success() {
    FileResponses.DownloadUrl expected =
        new FileResponses.DownloadUrl("http://minio/bucket/test.mp4?sign=xyz", Instant.now().plusSeconds(300));
    when(fileService.downloadUrlInternal("file_123")).thenReturn(expected);

    ResponseEntity<ApiResponse<FileResponses.DownloadUrl>> response =
        controller.downloadUrlInternal("file_123");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(200, response.getBody().code());
    assertEquals(expected.url(), response.getBody().data().url());
    verify(fileService).downloadUrlInternal("file_123");
  }
}
