package com.calles.platform.file.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS SDK 客户端配置。
 *
 * <p>将内部管理操作端点与客户端/外部审核可达的预签名端点物理分离，
 * 避免在预签名生成后使用脆弱的字符串替换破坏签名摘要。
 */
@Configuration
@ConditionalOnProperty(prefix = "file.storage.oss", name = "endpoint")
public class AliyunOssStorageConfiguration {

  /**
   * 使用内部管理/读写端点的 OSS 客户端 Bean。
   *
   * @param properties 文件服务运行参数
   * @return 管理端点 OSS 客户端实例
   */
  @Bean(destroyMethod = "shutdown")
  public OSS fileStorageOssClient(FileStorageProperties properties) {
    return client(properties.getOss().getEndpoint(), properties);
  }

  /**
   * 使用浏览器、客户端及公网审核服务可达端点生成预签名的 OSS 客户端 Bean。
   *
   * @param properties 文件服务运行参数
   * @return 预签名端点 OSS 客户端实例
   */
  @Bean(destroyMethod = "shutdown")
  public OSS filePresignOssClient(FileStorageProperties properties) {
    return client(properties.getOss().getPresignEndpoint(), properties);
  }

  /**
   * 根据已校验参数构造并配置 OSS SDK 客户端，不向外泄漏密钥。
   *
   * @param endpoint 目标接入点 URL
   * @param properties 存储配置属性
   * @return 构建完成的 OSS 客户端
   */
  private OSS client(String endpoint, FileStorageProperties properties) {
    // 步骤1：Bean 创建前执行启动期合法性校验，拒绝无效参数或空凭据
    properties.validate();

    FileStorageProperties.Storage storage = properties.getStorage();
    FileStorageProperties.Oss oss = properties.getOss();

    // 步骤2：配置网络连接与传输超时，防止慢连接长期耗尽工作线程
    ClientBuilderConfiguration clientConfig = new ClientBuilderConfiguration();
    clientConfig.setConnectionTimeout((int) storage.getConnectTimeout().toMillis());
    clientConfig.setSocketTimeout((int) storage.getReadTimeout().toMillis());

    // 步骤3：通过构建器注入凭据与端点，完成客户端实例化
    return new OSSClientBuilder()
        .build(endpoint, oss.getAccessKey(), oss.getSecretKey(), clientConfig);
  }
}
