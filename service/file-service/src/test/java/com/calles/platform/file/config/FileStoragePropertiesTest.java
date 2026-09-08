package com.calles.platform.file.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 文件配置启动期约束的单元测试。 */
class FileStoragePropertiesTest {
  /** 正常隔离 MinIO 参数应通过校验。 */
  @Test
  void validatesCompleteProperties() {
    FileStorageProperties properties = configured();
    assertDoesNotThrow(properties::validate);
  }

  /** PUT 签名不能长于或等于上传确认总期限。 */
  @Test
  void rejectsInvalidTtlRelationship() {
    FileStorageProperties properties = configured();
    properties.setUploadTtl(properties.getPutTtl());
    assertThrows(IllegalStateException.class, properties::validate);
  }

  /** @return 测试用完整非敏感配置 */
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
