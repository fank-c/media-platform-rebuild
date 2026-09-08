package com.calles.platform.file.config;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 文件服务运行基础设施配置，负责启动参数校验、UTC 时钟和有界确认执行器。 */
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
   * 创建确认摘要任务专用有界线程池。
   *
   * <p>使用 AbortPolicy 让 HTTP 层返回 503；禁止 CallerRunsPolicy 将大文件读取转移到请求线程。
   *
   * @return 固定工作线程和有限队列的执行器
   */
  @Bean(destroyMethod = "shutdown")
  public ThreadPoolExecutor fileConfirmExecutor() {
    int threads = properties.getConfirm().getThreads();
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "file-confirm-worker");
          thread.setDaemon(true);
          return thread;
        };
    return new ThreadPoolExecutor(
        threads,
        threads,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(properties.getConfirm().getQueueCapacity()),
        factory,
        new ThreadPoolExecutor.AbortPolicy());
  }
}
