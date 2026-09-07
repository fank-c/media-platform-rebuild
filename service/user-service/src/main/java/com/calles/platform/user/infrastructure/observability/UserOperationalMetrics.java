package com.calles.platform.user.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 用户资料链路的低基数业务指标，覆盖乐观锁冲突与账号创建事件消费结果。
 */
@Component
public class UserOperationalMetrics {

    /** HTTP 资料修改发生版本或生命周期竞争的次数。 */
    private final Counter revisionConflicts;
    /** 账号创建事件首次成功处理次数。 */
    private final Counter eventCreated;
    /** 账号创建事件命中已有正常资料的次数。 */
    private final Counter eventAlreadyExists;
    /** 账号创建事件跳过已停用资料的次数。 */
    private final Counter eventSkippedDisabled;
    /** 账号创建事件跳过逻辑删除墓碑的次数。 */
    private final Counter eventSkippedDeleted;
    /** 账号创建事件重复投递次数。 */
    private final Counter eventDuplicate;
    /** 账号创建事件处理失败尝试次数。 */
    private final Counter eventFailed;

    /**
     * 创建用户链路指标并只使用固定 outcome 标签，不暴露 accountId、eventId 或 traceId。
     *
     * @param registry Micrometer 指标注册表
     */
    public UserOperationalMetrics(MeterRegistry registry) {
        this.revisionConflicts = registry.counter("user_profile_revision_conflict_total");
        this.eventCreated = registry.counter("user_account_created_consume_total", "outcome", "created");
        this.eventAlreadyExists = registry.counter("user_account_created_consume_total", "outcome", "already_exists");
        this.eventSkippedDisabled = registry.counter("user_account_created_consume_total", "outcome", "skipped_disabled");
        this.eventSkippedDeleted = registry.counter("user_account_created_consume_total", "outcome", "skipped_deleted");
        this.eventDuplicate = registry.counter("user_account_created_consume_total", "outcome", "duplicate");
        this.eventFailed = registry.counter("user_account_created_consume_total", "outcome", "failed");
    }

    /** 记录一次资料乐观锁或生命周期条件竞争。 */
    public void recordRevisionConflict() {
        revisionConflicts.increment();
    }

    /**
     * 记录一条账号创建事件首次处理结果，并保留墓碑或停用跳过的独立可观测性。
     *
     * @param outcome 资料初始化返回的固定结果
     */
    public void recordEventProcessed(String outcome) {
        switch (outcome) {
            case "CREATED" -> eventCreated.increment();
            case "ALREADY_EXISTS" -> eventAlreadyExists.increment();
            case "SKIPPED_DISABLED" -> eventSkippedDisabled.increment();
            case "SKIPPED_DELETED" -> eventSkippedDeleted.increment();
            default -> throw new IllegalArgumentException("未知资料初始化结果: " + outcome);
        }
    }

    /** 记录一条账号创建事件被幂等表识别为重复投递。 */
    public void recordEventDuplicate() {
        eventDuplicate.increment();
    }

    /** 记录一次非法消息或临时异常导致的消费失败尝试。 */
    public void recordEventFailed() {
        eventFailed.increment();
    }
}
