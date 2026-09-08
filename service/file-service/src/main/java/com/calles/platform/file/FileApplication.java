package com.calles.platform.file;

import com.calles.platform.file.config.FileStorageProperties;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 文件服务启动入口，只扫描 file-service 自有 Mapper，并启用受配置控制的过期清理调度。 */
@SpringBootApplication
@EnableScheduling
@MapperScan(
    basePackages = "com.calles.platform.file.infrastructure.persistence",
    annotationClass = Mapper.class)
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileApplication {
  /**
   * 启动文件服务；外部请求仍应由网关完成认证并传递受信任的身份 Header。
   *
   * @param args JVM 启动参数
   */
  public static void main(String[] args) {
    SpringApplication.run(FileApplication.class, args);
  }
}
