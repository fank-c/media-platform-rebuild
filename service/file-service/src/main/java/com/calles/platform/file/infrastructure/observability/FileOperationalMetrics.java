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

  /** 成功建立数据库删除闸门次数。 */
  private final Counter deletionRequested;

  /** 远端删除明确完成且逻辑墓碑已落库次数。 */
  private final Counter deletionSuccess;

  /** 远端删除或最终墓碑写入未确认次数。 */
  private final Counter deletionFailure;

  /** 删除恢复扫描发现仍未收敛记录的次数。 */
  private final Counter deletionStuck;

  /** V2 确认闸门建立、成功、失败和恢复卡住次数。 */
  private final Counter verificationRequested;

  private final Counter verificationSuccess;
  private final Counter verificationFailure;
  private final Counter verificationStuck;

  /** V2 staging 清理失败次数。 */
  private final Counter stagingCleanupFailure;

  /**
   * @param registry 应用指标注册表
   */
  public FileOperationalMetrics(MeterRegistry registry) {
    uploadSuccess = Counter.builder("file.upload.success").register(registry);
    operationFailure = Counter.builder("file.operation.failure").register(registry);
    confirmationRejected = Counter.builder("file.confirmation.rejected").register(registry);
    cleanupSuccess = Counter.builder("file.cleanup.success").register(registry);
    cleanupFailure = Counter.builder("file.cleanup.failure").register(registry);
    deletionRequested = Counter.builder("file.deletion.requested").register(registry);
    deletionSuccess = Counter.builder("file.deletion.success").register(registry);
    deletionFailure = Counter.builder("file.deletion.failure").register(registry);
    deletionStuck = Counter.builder("file.deletion.stuck").register(registry);
    verificationRequested = Counter.builder("file.verification.requested").register(registry);
    verificationSuccess = Counter.builder("file.verification.success").register(registry);
    verificationFailure = Counter.builder("file.verification.failure").register(registry);
    verificationStuck = Counter.builder("file.verification.stuck").register(registry);
    stagingCleanupFailure = Counter.builder("file.staging.cleanup.failure").register(registry);
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

  /** 记录删除闸门建立。 */
  public void deletionRequested() {
    deletionRequested.increment();
  }

  /** 记录删除完成并已写入逻辑墓碑。 */
  public void deletionSuccess() {
    deletionSuccess.increment();
  }

  /** 记录删除远端结果或最终数据库写入未确认。 */
  public void deletionFailure() {
    deletionFailure.increment();
  }

  /** 记录恢复扫描遇到尚未收敛的删除中记录。 */
  public void deletionStuck() {
    deletionStuck.increment();
  }

  /** 记录 V2 确认闸门建立。 */
  public void verificationRequested() {
    verificationRequested.increment();
  }

  /** 记录 V2 条件 copy 和完成 CAS 成功。 */
  public void verificationSuccess() {
    verificationSuccess.increment();
  }

  /** 记录 V2 HEAD、copy 或完成 CAS 未收敛。 */
  public void verificationFailure() {
    verificationFailure.increment();
  }

  /** 记录恢复扫描发现仍处于 VERIFYING 的记录。 */
  public void verificationStuck() {
    verificationStuck.increment();
  }

  /** 记录完成后的 staging 清理失败。 */
  public void stagingCleanupFailure() {
    stagingCleanupFailure.increment();
  }
}
