package com.calles.platform.file.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.CopyObjectRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.StorageType;
import com.calles.platform.file.exception.ObjectStorageException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 阿里云 OSS 存储适配器的单元测试，覆盖操作封装、端点分离与异常映射。 */
class AliyunOssObjectStorageClientTest {

  private OSS storageClient;
  private OSS presignClient;
  private FileStorageProperties properties;
  private AliyunOssObjectStorageClient client;

  @BeforeEach
  void setUp() {
    storageClient = mock(OSS.class);
    presignClient = mock(OSS.class);
    properties = new FileStorageProperties();
    properties.getOss().setBucket("test-bucket");
    properties.getOss().setEndpoint("https://oss-internal.example.com");
    properties.getOss().setPresignEndpoint("https://oss-public.example.com");
    properties.getOss().setRegion("cn-beijing");
    properties.getOss().setAccessKey("ak");
    properties.getOss().setSecretKey("sk");

    client = new AliyunOssObjectStorageClient(storageClient, presignClient, properties);
  }

  /** 返回受控的 ALIYUN_OSS 存储类型枚举。 */
  @Test
  void returnsOssStorageType() {
    assertEquals(StorageType.ALIYUN_OSS, client.storageType());
  }

  /** 流式上传应向管理客户端传递正确的 Bucket、Key、MIME 与 Content-Length。 */
  @Test
  void putsObjectWithCorrectMetadata() {
    ByteArrayInputStream input = new ByteArrayInputStream("hello".getBytes());
    client.put("assets/demo.txt", "text/plain", input, 5L);

    ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(storageClient).putObject(captor.capture());

    PutObjectRequest request = captor.getValue();
    assertEquals("test-bucket", request.getBucketName());
    assertEquals("assets/demo.txt", request.getKey());
    assertEquals("text/plain", request.getMetadata().getContentType());
    assertEquals(5L, request.getMetadata().getContentLength());
  }

  /** 上传发生底层异常时应被统一转换为 UNAVAILABLE 领域异常。 */
  @Test
  void translatesPutFailureToUnavailable() {
    doThrow(new RuntimeException("network timeout"))
        .when(storageClient)
        .putObject(any(PutObjectRequest.class));

    ObjectStorageException ex =
        assertThrows(
            ObjectStorageException.class,
            () -> client.put("key", "text/plain", new ByteArrayInputStream(new byte[0]), 0L));

    assertEquals(ObjectStorageException.Category.UNAVAILABLE, ex.getCategory());
  }

  /** HEAD 正常返回时准确映射 size、etag、lastModified 与 contentType。 */
  @Test
  void headsObjectMetadataAccurately() {
    ObjectMetadata metadata = mock(ObjectMetadata.class);
    when(metadata.getContentLength()).thenReturn(1024L);
    when(metadata.getETag()).thenReturn("etag-12345");
    when(metadata.getContentType()).thenReturn("video/mp4");
    Date lastModified = new Date(1700000000000L);
    when(metadata.getLastModified()).thenReturn(lastModified);

    when(storageClient.getObjectMetadata("test-bucket", "video.mp4")).thenReturn(metadata);

    ObjectStorageClient.ObjectHead head = client.head("video.mp4");
    assertNotNull(head);
    assertEquals(1024L, head.size());
    assertEquals("etag-12345", head.etag());
    assertEquals("video/mp4", head.contentType());
    assertEquals(lastModified.toInstant(), head.lastModified());
  }

  /** HEAD 遇到 NoSuchKey 错误码时应精准分类为 NOT_FOUND。 */
  @Test
  void translatesHeadNoSuchKeyToNotFound() {
    OSSException ossException = new OSSException("Object not found", "NoSuchKey", "req-1", "host-1", "header-1", "sig-1", "GET");
    when(storageClient.getObjectMetadata("test-bucket", "missing.txt")).thenThrow(ossException);

    ObjectStorageException ex =
        assertThrows(ObjectStorageException.class, () -> client.head("missing.txt"));

    assertEquals(ObjectStorageException.Category.NOT_FOUND, ex.getCategory());
  }

  /** 打开输入流应从 OSSObject 获取真实流实例。 */
  @Test
  void opensObjectContentStream() {
    OSSObject ossObject = mock(OSSObject.class);
    ByteArrayInputStream expectedStream = new ByteArrayInputStream("stream-content".getBytes());
    when(ossObject.getObjectContent()).thenReturn(expectedStream);
    when(storageClient.getObject("test-bucket", "file.txt")).thenReturn(ossObject);

    InputStream stream = client.open("file.txt");
    assertNotNull(stream);
  }

  /** 删除对象应委托给管理客户端。 */
  @Test
  void deletesObjectThroughStorageClient() {
    client.delete("assets/to-delete.txt");
    verify(storageClient).deleteObject("test-bucket", "assets/to-delete.txt");
  }

  /** 生成 V1 PUT 签名应使用预签名客户端且携带受控 Content-Type。 */
  @Test
  void presignsPutUrlThroughPresignClient() throws Exception {
    URL mockUrl = URI.create("https://oss-public.example.com/test-bucket/assets/f1?sign=xxx").toURL();
    when(presignClient.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
        .thenReturn(mockUrl);

    ObjectStorageClient.PresignedUrl result =
        client.presignPut("assets/f1", "image/png", Duration.ofMinutes(10));

    assertNotNull(result);
    assertEquals(mockUrl.toString(), result.url());
    assertEquals("image/png", result.requiredHeaders().get("Content-Type"));

    ArgumentCaptor<GeneratePresignedUrlRequest> captor =
        ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
    verify(presignClient).generatePresignedUrl(captor.capture());
    GeneratePresignedUrlRequest request = captor.getValue();
    assertEquals(HttpMethod.PUT, request.getMethod());
    assertEquals("image/png", request.getContentType());
  }

  /** V2 checksum PUT 签名在 POC 前必须明确拒绝。 */
  @Test
  void rejectsPresignPutWithChecksumInStageOne() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> client.presignPut("staging/f1", "image/png", "base64==", Duration.ofMinutes(10)));
  }

  /** 条件 CopyObject 应携带 matchingETagConstraints 约束。 */
  @Test
  void copiesObjectWithEtagConstraint() {
    client.copy("staging/f1", "permanent/f1", "expected-etag");

    ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
    verify(storageClient).copyObject(captor.capture());

    CopyObjectRequest request = captor.getValue();
    assertEquals("test-bucket", request.getSourceBucketName());
    assertEquals("staging/f1", request.getSourceKey());
    assertEquals("test-bucket", request.getDestinationBucketName());
    assertEquals("permanent/f1", request.getDestinationKey());
    assertEquals(1, request.getMatchingETagConstraints().size());
    assertEquals("expected-etag", request.getMatchingETagConstraints().get(0));
  }

  /** 条件 CopyObject 遇到 PreconditionFailed 时应转换为 CONTENT_MISMATCH 异常。 */
  @Test
  void translatesCopyPreconditionFailedToContentMismatch() {
    OSSException ossException =
        new OSSException("Precondition failed", "PreconditionFailed", "req-1", "host-1", "h", "s", "PUT");
    when(storageClient.copyObject(any(CopyObjectRequest.class))).thenThrow(ossException);

    ObjectStorageException ex =
        assertThrows(
            ObjectStorageException.class,
            () -> client.copy("staging/f1", "permanent/f1", "etag-mismatch"));

    assertEquals(ObjectStorageException.Category.CONTENT_MISMATCH, ex.getCategory());
  }

  /** 生成 GET 下载预签名 URL 时应调用 presignClient。 */
  @Test
  void presignsGetUrlThroughPresignClient() throws Exception {
    URL mockUrl = URI.create("https://oss-public.example.com/test-bucket/permanent/f1?sign=yyy").toURL();
    when(presignClient.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
        .thenReturn(mockUrl);

    ObjectStorageClient.PresignedUrl result =
        client.presignGet("permanent/f1", Duration.ofMinutes(5));

    assertNotNull(result);
    assertEquals(mockUrl.toString(), result.url());

    ArgumentCaptor<GeneratePresignedUrlRequest> captor =
        ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
    verify(presignClient).generatePresignedUrl(captor.capture());
    assertEquals(HttpMethod.GET, captor.getValue().getMethod());
  }
}
