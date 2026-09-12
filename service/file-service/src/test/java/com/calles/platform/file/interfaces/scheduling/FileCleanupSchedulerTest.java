package com.calles.platform.file.interfaces.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.calles.platform.file.application.cleanup.FileCleanupService;
import com.calles.platform.file.config.FileStorageProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时清理调度装配与执行行为测试。
 *
 * <p>验证 @Scheduled 注解参数在纯数字毫秒配置下可被 Spring 正常解析，
 * 并确认如果传入带单位字符串（如 60s）会导致上下文启动解析失败。
 */
class FileCleanupSchedulerTest {

  /** 基础上下文运行器，启用调度能力并注册测试配置。 */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestSchedulingConfiguration.class);

  /**
   * 验证在默认配置（使用注解默认值 60000）下，Spring 调度器能成功启动且 Scheduler Bean 正常就绪。
   */
  @Test
  void startsSuccessfullyWithDefaultMillisecondInterval() {
    // 步骤 1：不注入外部属性，依赖注解内默认值 60000 启动上下文
    contextRunner.run(
        context -> {
          // 步骤 2：断言容器启动成功且包含 FileCleanupScheduler
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(FileCleanupScheduler.class);
        });
  }

  /**
   * 验证外部配置显式传入纯数字毫秒字符串时，Spring 调度器能成功启动。
   */
  @Test
  void startsSuccessfullyWithCustomMillisecondIntervalProperty() {
    // 步骤 1：注入纯数字毫秒配置
    contextRunner
        .withPropertyValues("file.cleanup.interval=30000")
        .run(
            context -> {
              // 步骤 2：断言容器启动成功
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(FileCleanupScheduler.class);
            });
  }

  /**
   * 验证若配置类似 60s 的带单位字符串，Spring 调度器将抛出非法参数异常导致启动失败。
   */
  @Test
  void failsStartupWhenIntervalHasTimeUnitSuffix() {
    // 步骤 1：故意注入带单位的非法字符串 60s
    contextRunner
        .withPropertyValues("file.cleanup.interval=60s")
        .run(
            context -> {
              // 步骤 2：断言容器启动失败且包含 fixedDelayString 解析异常与 IllegalStateException 根因
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("Invalid fixedDelayString value \"60s\"");
            });
  }

  /**
   * 验证当未启用清理开关时，trigger 不触发任何清理用例。
   */
  @Test
  void doesNotTriggerCleanupWhenDisabled() {
    // 步骤 1：准备禁用清理的配置与 mock 用例
    FileCleanupService cleanupService = mock(FileCleanupService.class);
    FileStorageProperties properties = new FileStorageProperties();
    properties.getCleanup().setEnabled(false);

    FileCleanupScheduler scheduler = new FileCleanupScheduler(cleanupService, properties);

    // 步骤 2：执行调度触发
    scheduler.trigger();

    // 步骤 3：验证未调用任何清理逻辑
    verify(cleanupService, never()).cleanup(100, 5, Duration.ofSeconds(30));
  }

  /**
   * 验证当启用清理开关时，trigger 依次触发四类清理与恢复用例。
   */
  @Test
  void triggersAllCleanupPhasesWhenEnabled() {
    // 步骤 1：准备启用清理的配置与 mock 用例
    FileCleanupService cleanupService = mock(FileCleanupService.class);
    FileStorageProperties properties = new FileStorageProperties();
    properties.getCleanup().setEnabled(true);
    properties.getCleanup().setBatchSize(50);
    properties.getCleanup().setMaxBatches(3);
    properties.getCleanup().setRunTimeout(Duration.ofSeconds(10));

    FileCleanupScheduler scheduler = new FileCleanupScheduler(cleanupService, properties);

    // 步骤 2：执行调度触发
    scheduler.trigger();

    // 步骤 3：验证依次触发所有清理与恢复阶段
    verify(cleanupService).cleanup(50, 3, Duration.ofSeconds(10));
    verify(cleanupService).recoverVerification(50, 3, Duration.ofSeconds(10));
    verify(cleanupService).cleanupStaging(50, 3, Duration.ofSeconds(10));
    verify(cleanupService).recoverDeletion(50, 3, Duration.ofSeconds(10));
  }

  /**
   * 测试用轻量调度配置，仅装配调度注解处理器和调度器本身。
   */
  @Configuration
  @EnableScheduling
  static class TestSchedulingConfiguration {

    /**
     * @return 模拟的清理用例 Bean
     */
    @Bean
    FileCleanupService fileCleanupService() {
      return mock(FileCleanupService.class);
    }

    /**
     * @return 默认配置 Bean
     */
    @Bean
    FileStorageProperties fileStorageProperties() {
      return new FileStorageProperties();
    }

    /**
     * @param cleanupService 清理用例
     * @param properties 配置
     * @return 调度器 Bean
     */
    @Bean
    FileCleanupScheduler fileCleanupScheduler(
        FileCleanupService cleanupService, FileStorageProperties properties) {
      return new FileCleanupScheduler(cleanupService, properties);
    }
  }
}
