package com.calles.platform.user.infrastructure.scheduling;

import com.calles.platform.user.infrastructure.outbox.dispatch.UserOutboxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 用户发件箱定时自愈与补偿扫描任务。
 *
 * <p>定期扫描 user_outbox 表中未成功投递、租约超期或重试到达允许时间的记录，
 * 通过 CAS 抢占并重试投递，确保领域事件 At-least-once 绝对不丢失。</p>
 */
@Component
public class UserOutboxScanJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserOutboxScanJob.class);

    private final UserOutboxDispatcher dispatcher;

    public UserOutboxScanJob(UserOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 固定间隔触发一轮扫描。
     */
    @Scheduled(fixedDelayString = "${user.outbox.poll-interval:5s}")
    public void scanAndDispatch() {
        try {
            dispatcher.dispatchScanBatch();
        } catch (Exception e) {
            LOGGER.error("User Outbox 定时扫描执行失败: {}", e.getMessage(), e);
        }
    }
}
