package com.calles.platform.interaction.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.calles.platform.interaction.application.outbox.InteractionOutboxCleanupService;
import com.calles.platform.interaction.config.InteractionOutboxProperties;
import com.calles.platform.interaction.infrastructure.outbox.dispatch.InteractionOutboxDispatcher;
import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** 验证 Spring 真正注册三条 Outbox 定时任务时可解析带单位和纯数字间隔。 */
class InteractionOutboxSchedulingConfigurationTest {

    /** Spring 调度处理器启动时须成功转换默认值和环境覆盖值。 */
    @Test
    void shouldRegisterSchedulesWithDurationAndNumericOverrides() {
        verifyRegistration("5s", "1h");
        verifyRegistration("5000", "3600000");
    }

    /** 使用独立上下文注册任务，关闭时清理调度线程。 */
    private void verifyRegistration(String pollInterval, String cleanupInterval) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getSystemProperties().put("interaction.outbox.poll-interval", pollInterval);
            context.getEnvironment().getSystemProperties().put("interaction.outbox.cleanup-interval", cleanupInterval);
            context.register(Schedules.class);
            context.refresh();
            assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(3);
        }
    }

    /** 仅加载 Outbox 三条调度链路，不连接 MySQL、RabbitMQ 或 Nacos。 */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Schedules {
        /** 注册派发扫描任务。 */
        @Bean
        InteractionOutboxScanJob scanJob() {
            return new InteractionOutboxScanJob(mock(InteractionOutboxDispatcher.class));
        }

        /** 注册清理任务。 */
        @Bean
        InteractionOutboxCleanupJob cleanupJob() {
            return new InteractionOutboxCleanupJob(mock(InteractionOutboxCleanupService.class));
        }

        /** 注册积压状态采样任务。 */
        @Bean
        InteractionOutboxBacklogMetrics backlogMetrics() {
            return new InteractionOutboxBacklogMetrics(mock(InteractionOutboxRepository.class),
                    new InteractionOutboxProperties(), Clock.systemUTC(), new SimpleMeterRegistry());
        }
    }
}
