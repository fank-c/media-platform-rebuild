package com.calles.platform.file.interfaces.scheduling;

import com.calles.platform.file.application.cleanup.FileCleanupService;
import com.calles.platform.file.config.FileStorageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 文件过期清理的调度入口，只触发应用用例，不在调度层承载生命周期或对象删除逻辑。 */
@Component
public class FileCleanupScheduler {
  /** 清理应用用例。 */
  private final FileCleanupService cleanupService;

  /** 是否启用及批次预算。 */
  private final FileStorageProperties properties;

  /**
   * @param cleanupService 清理用例
   * @param properties 清理参数
   */
  public FileCleanupScheduler(FileCleanupService cleanupService, FileStorageProperties properties) {
    this.cleanupService = cleanupService;
    this.properties = properties;
  }

  /** 默认不执行；仅隔离 bucket 显式启用时才处理有限数量的过期记录。 */
  @Scheduled(fixedDelayString = "${file.cleanup.interval:60s}")
  public void trigger() {
    if (properties.getCleanup().isEnabled())
      cleanupService.cleanup(
          properties.getCleanup().getBatchSize(),
          properties.getCleanup().getMaxBatches(),
          properties.getCleanup().getRunTimeout());
  }
}
