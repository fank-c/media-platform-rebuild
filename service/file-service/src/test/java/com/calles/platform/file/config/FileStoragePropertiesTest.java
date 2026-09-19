package com.calles.platform.file.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 文件配置启动期约束的单元测试。 */
class FileStoragePropertiesTest {
  /** 正常隔离阿里云 OSS 参数应通过校验。 */
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

  /** V2 开关开启时必须同时启用清理恢复闭环。 */
  @Test
  void rejectsV2WithoutCleanup() {
    FileStorageProperties properties = configured();
    properties.getDirectUploadV2().setEnabled(true);
    assertThrows(IllegalStateException.class, properties::validate);
  }

  /** staging 与 permanent 不能相同或互相嵌套。 */
  @Test
  void rejectsNestedObjectPrefixes() {
    FileStorageProperties properties = configured();
    properties.getOss().setStagingPrefix("objects");
    properties.getOss().setPermanentPrefix("objects/permanent");
    assertThrows(IllegalStateException.class, properties::validate);
  }

  /** 配置 rootPrefix 时应自动为 staging、permanent、legacy 拼接路径隔离前缀并去除多余斜杠。 */
  @Test
  void validatesRootPrefixIsolation() {
    FileStorageProperties properties = configured();
    properties.getOss().setRootPrefix("/item/media-platform/");
    properties.getOss().setStagingPrefix("/staging/");
    properties.getOss().setPermanentPrefix("/permanent/");

    org.junit.jupiter.api.Assertions.assertEquals(
        "item/media-platform/staging", properties.getOss().getStagingPrefix());
    org.junit.jupiter.api.Assertions.assertEquals(
        "item/media-platform/permanent", properties.getOss().getPermanentPrefix());
    org.junit.jupiter.api.Assertions.assertEquals(
        "item/media-platform/assets", properties.getOss().getLegacyPrefix());
    assertDoesNotThrow(properties::validate);
  }

  /** 端点未显式携带协议时应自动规范化为 https://。 */
  @Test
  void normalizesEndpointWithoutProtocol() {
    FileStorageProperties properties = configured();
    properties.getOss().setEndpoint("oss-cn-beijing.aliyuncs.com");
    properties.getOss().setPresignEndpoint("oss-cn-beijing.aliyuncs.com");

    org.junit.jupiter.api.Assertions.assertEquals(
        "https://oss-cn-beijing.aliyuncs.com", properties.getOss().getEndpoint());
    org.junit.jupiter.api.Assertions.assertEquals(
        "https://oss-cn-beijing.aliyuncs.com", properties.getOss().getPresignEndpoint());
  }

  /** 测试 accessKeyId 与 accessKeySecret 别名与底层 accessKey/secretKey 双向读写同步。 */
  @Test
  void validatesAccessKeyAliases() {
    FileStorageProperties properties = new FileStorageProperties();
    properties.getOss().setAccessKeyId("aliyun-ak-test");
    properties.getOss().setAccessKeySecret("aliyun-sk-test");

    org.junit.jupiter.api.Assertions.assertEquals("aliyun-ak-test", properties.getOss().getAccessKey());
    org.junit.jupiter.api.Assertions.assertEquals("aliyun-ak-test", properties.getOss().getAccessKeyId());
    org.junit.jupiter.api.Assertions.assertEquals("aliyun-sk-test", properties.getOss().getSecretKey());
    org.junit.jupiter.api.Assertions.assertEquals("aliyun-sk-test", properties.getOss().getAccessKeySecret());
  }

  /**
   * @return 测试用完整非敏感配置
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
