package com.calles.platform.content.infrastructure.scheduling;

import com.calles.platform.content.infrastructure.outbox.dispatch.ContentOutboxDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Content Outbox 定时自愈补偿扫描任务 (ContentOutboxScanJob)。
 *
 * <p>职责与机制说明：
 * <ul>
 *   <li><b>所属边界</b>：发件箱后台自愈扫描器；</li>
 *   <li><b>补偿兜底</b>：周期性唤醒，扫描拉取超时未投递、网络抖动重试到期或快速通知丢失的 Outbox 事件；</li>
 *   <li><b>安全间隔</b>：使用 {@code fixedDelayString}，前一轮批次执行彻底结束后才开始倒计时下一轮，杜绝单实例内部任务重叠。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "content.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContentOutboxScanJob {

    /** 统一分发调度器。 */
    private final ContentOutboxDispatcher dispatcher;

    /**
     * 周期性执行一轮发件箱候选扫描与分发。
     * 参数通过纯数字毫秒字符串读取，默认 1000ms。
     */
    @Scheduled(fixedDelayString = "${content.outbox.poll-interval:1000}")
    public void runBatch() {
        dispatcher.dispatchScanBatch();
    }
}
