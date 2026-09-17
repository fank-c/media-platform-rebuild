package com.calles.platform.file.config;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 文件服务运行基础设施配置，负责启动参数校验、UTC 时钟和异步确认执行器。 */
@Configuration
public class FileRuntimeConfiguration {
  /** 已绑定的文件运行参数。 */
  private final FileStorageProperties properties;

  /** @param properties 文件运行参数 */
  public FileRuntimeConfiguration(FileStorageProperties properties) {
    this.properties = properties;
  }

  /** 启动前拒绝空 bucket、空密钥、无效 TTL 或无界任务参数。 */
  @PostConstruct
  public void validateProperties() {
    properties.validate();
  }

  /** @return 使用 UTC 的统一业务时钟，便于测试固定时间和跨时区比较 */
  @Bean
  public Clock fileClock() {
    return Clock.systemUTC();
  }

  /**
   * 创建确认摘要任务专用虚拟线程执行器。
   *
   * <p>基于 Java 21 虚拟线程异步处理直传文件确认与 MinIO 元数据校验，等待对象存储网络 I/O 时自动挂起，
   * 消除固定工作线程瓶颈与队列满溢拒绝。
   *
   * @return 基于虚拟线程的执行器
   */
  @Bean
  public Executor fileConfirmExecutor() {
    ThreadFactory factory = Thread.ofVirtual().name("file-confirm-vt-", 1).factory();
    return Executors.newThreadPerTaskExecutor(factory);
  }
}
