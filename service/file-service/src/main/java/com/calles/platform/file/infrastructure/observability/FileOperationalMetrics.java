package com.calles.platform.file.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 文件服务最小运行指标。
 *
 * <p>指标不以 fileId、userId、object key 或签名 URL 作为标签，避免高基数和敏感信息泄露。
 */
@Component
public class FileOperationalMetrics {
  /** 成功同步上传次数。 */
  private final Counter uploadSuccess;

  /** 上传或确认失败次数。 */
  private final Counter operationFailure;

  /** 确认任务被有界队列拒绝次数。 */
  private final Counter confirmationRejected;

  /** 清理成功删除或确认不存在对象次数。 */
  private final Counter cleanupSuccess;

  /** 清理远端失败次数。 */
  private final Counter cleanupFailure;

  /** @param registry 应用指标注册表 */
  public FileOperationalMetrics(MeterRegistry registry) {
    uploadSuccess = Counter.builder("file.upload.success").register(registry);
    operationFailure = Counter.builder("file.operation.failure").register(registry);
    confirmationRejected = Counter.builder("file.confirmation.rejected").register(registry);
    cleanupSuccess = Counter.builder("file.cleanup.success").register(registry);
    cleanupFailure = Counter.builder("file.cleanup.failure").register(registry);
  }

  /** 记录同步上传完成。 */
  public void uploadSuccess() {
    uploadSuccess.increment();
  }

  /** 记录不暴露具体对象的失败分类。 */
  public void operationFailure() {
    operationFailure.increment();
  }

  /** 记录确认队列拒绝。 */
  public void confirmationRejected() {
    confirmationRejected.increment();
  }

  /** 记录一条清理远端操作完成。 */
  public void cleanupSuccess() {
    cleanupSuccess.increment();
  }

  /** 记录一条清理远端操作失败。 */
  public void cleanupFailure() {
    cleanupFailure.increment();
  }
}
