package com.calles.platform.auth.infrastructure.scheduling;

import com.calles.platform.auth.infrastructure.outbox.AuthOutboxDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Auth Outbox 的独立扫描恢复任务。
 *
 * <p>快速执行器饱和、提示丢失、服务重启和补齐事件都依赖本任务最终恢复；它不投递到注册快速队列，
 * 避免持续注册挤占恢复进度。</p>
 */
@Component
@ConditionalOnProperty(prefix = "auth.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthOutboxScanJob {

    /** 统一按 ID 领取和发送入口。 */
    private final AuthOutboxDispatcher dispatcher;

    /**
     * @param dispatcher 统一分发器
     */
    public AuthOutboxScanJob(AuthOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 扫描一轮候选；fixedDelay 从本轮完成后开始，保证同一实例只有一个扫描进度槽位。
     * 定时任务参数使用纯数字毫秒字符串（默认 1000ms），避免 @Scheduled 无法解析类似 1s 的带单位字符串。
     */
    @Scheduled(fixedDelayString = "${auth.outbox.poll-interval:1000}")
    public void runBatch() {
        dispatcher.dispatchScanBatch();
    }
}
