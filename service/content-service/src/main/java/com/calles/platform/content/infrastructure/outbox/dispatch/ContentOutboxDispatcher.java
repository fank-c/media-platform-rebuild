package com.calles.platform.content.infrastructure.outbox.dispatch;

import com.calles.platform.content.config.ContentOutboxProperties;
import com.calles.platform.content.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.content.infrastructure.outbox.persistence.ContentOutboxRepository;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Content Outbox 的统一任务分发调度器。
 *
 * <p>职责与机制说明：
 * <ul>
 *   <li><b>所属边界</b>：发件箱分发中枢；</li>
 *   <li><b>双通道收敛</b>：无论是来自事务提交后的快速通知 (Fast Dispatch)，还是来自定时自愈扫描 (Scan Batch)，
 *       均通过本组件调用同一套 CAS 认领与发送逻辑，天然消除重复投递隐患；</li>
 *   <li><b>独立节点标识</b>：为当前应用运行进程生成唯一的 {@code owner}，用于记录分布式租约归属。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class ContentOutboxDispatcher {

    /** 发件箱持久化仓储。 */
    private final ContentOutboxRepository repository;

    /** 单条消息发布执行器。 */
    private final ContentOutboxPublisher publisher;

    /** 发件箱运行配置属性。 */
    private final ContentOutboxProperties properties;

    /** 当前进程实例的持久唯一认领者标识。 */
    private final String owner = UUID.randomUUID().toString();

    public ContentOutboxDispatcher(ContentOutboxRepository repository,
                                   ContentOutboxPublisher publisher,
                                   ContentOutboxProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
    }

    /**
     * 按指定事件 ID 执行单条认领与分发投递。
     *
     * @param eventId 待分发的事件全局唯一 ID
     */
    public void dispatchByEventId(String eventId) {
        // 步骤 1：全局总开关校验
        if (!properties.isEnabled()) {
            log.debug("Outbox 总投递开关已关闭，跳过分发: eventId={}", eventId);
            return;
        }

        // 步骤 2：CAS 原子条件认领租约并获取快照
        ClaimedOutboxMessage claimed = repository.claimByEventId(
                eventId,
                owner,
                properties.getLease(),
                properties.getMaxAttempts()
        );

        // 步骤 3：若成功抢占租约，立即执行网络投递
        if (claimed != null) {
            publisher.publish(claimed);
            return;
        }

        // 步骤 4：若未成功认领，评估是否属于重试耗尽的超限记录并收敛为 FAILED
        if (repository.markExhaustedIfEligible(eventId, properties.getMaxAttempts())) {
            log.warn("Outbox 事件重试次数已耗尽并标记为 FAILED 终态: eventId={}", eventId);
        }
    }

    /**
     * 执行一轮定时扫描候选批次，按序分发并收敛超限消息。
     */
    public void dispatchScanBatch() {
        // 步骤 1：检查总开关
        if (!properties.isEnabled()) {
            return;
        }

        try {
            // 步骤 2：扫描当前可抢占的候选事件 ID 列表
            List<String> eventIds = repository.findClaimableEventIds(
                properties.getBatchSize(),
                properties.getMaxAttempts()
            );

            // 步骤 3：逐条抢占并发送，单条失败不影响其他候选处理
            for (String eventId : eventIds) {
                try {
                    dispatchByEventId(eventId);
                } catch (Exception exception) {
                    log.error("Outbox 扫描单条事件派发异常: eventId={}", eventId, exception);
                }
            }

            // 步骤 4：处理超时耗尽的历史遗留事件
            List<String> exhaustedIds = repository.findExhaustedEventIds(
                properties.getBatchSize(),
                properties.getMaxAttempts()
            );
            for (String eventId : exhaustedIds) {
                repository.markExhaustedIfEligible(eventId, properties.getMaxAttempts());
            }
        } catch (Exception e) {
            log.error("Outbox 扫描批次执行异常", e);
        }
    }
}
