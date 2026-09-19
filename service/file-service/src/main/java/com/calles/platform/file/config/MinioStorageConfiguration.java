package com.calles.platform.file.config;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO SDK 客户端配置。
 *
 * <p>读写端点和预签名端点可不同，避免在签名后通过字符串替换 host 破坏签名。
 */
@Configuration
@ConditionalOnProperty(prefix = "file.storage.minio", name = "endpoint")
public class MinioStorageConfiguration {
  /** @return 使用内部管理端点的 MinIO 客户端 */
  @Bean
  public MinioClient fileStorageMinioClient(FileStorageProperties properties) {
    return client(properties.getMinio().getEndpoint(), properties);
  }

  /** @return 使用浏览器或客户端可达端点生成签名的 MinIO 客户端 */
  @Bean
  public MinioClient filePresignMinioClient(FileStorageProperties properties) {
    return client(properties.getMinio().getPresignEndpoint(), properties);
  }

  /** 根据已校验参数创建 SDK 客户端，不输出任何密钥。 */
  private MinioClient client(String endpoint, FileStorageProperties properties) {
    // SDK bean 创建前再次校验，避免因 Configuration 实例化顺序而延后暴露空密钥或无效超时。
    properties.validate();
    FileStorageProperties.Storage storage = properties.getStorage();
    OkHttpClient httpClient =
        new OkHttpClient.Builder()
            // 传输超时约束的是 SDK 请求，不等于 Future 取消已关闭底层 I/O。
            .connectTimeout(storage.getConnectTimeout())
            .readTimeout(storage.getReadTimeout())
            .callTimeout(storage.getCallTimeout())
            .build();
    return MinioClient.builder()
        .endpoint(endpoint)
        .credentials(properties.getMinio().getAccessKey(), properties.getMinio().getSecretKey())
        .httpClient(httpClient)
        .build();
  }
}
