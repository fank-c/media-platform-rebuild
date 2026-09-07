package com.calles.platform.auth.infrastructure.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.auth.config.AuthOutboxProperties;
import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** AuthOutboxDispatcher 对快速与扫描共用按 ID 领取入口的测试。 */
@ExtendWith(MockitoExtension.class)
class AuthOutboxDispatcherTest {

    @Mock private AuthOutboxRepository repository;
    @Mock private AuthOutboxPublisher publisher;
    @Mock private AuthOperationalMetrics metrics;
    private AuthOutboxProperties properties;
    private AuthOutboxDispatcher dispatcher;

    /** 为每个用例启用总发送并设置固定领取参数。 */
    @BeforeEach
    void setUp() {
        properties = new AuthOutboxProperties();
        properties.setLease(Duration.ofSeconds(30));
        properties.setMaxAttempts(20);
        properties.setBatchSize(10);
        dispatcher = new AuthOutboxDispatcher(repository, publisher, properties, metrics);
    }

    /** 条件领取成功后才调用统一发送器。 */
    @Test
    void dispatchByIdClaimsThenPublishes() {
        ClaimedOutboxMessage claimed = new ClaimedOutboxMessage("event-1", "{}", "token", 1);
        when(repository.claimByEventId(eq("event-1"), any(), eq(Duration.ofSeconds(30)), eq(20)))
                .thenReturn(claimed);

        dispatcher.dispatchByEventId("event-1");

        verify(publisher).publish(claimed);
        verify(metrics).recordOutboxClaim("claimed");
    }

    /** 竞争失败后仅尝试收敛耗尽记录，不向 RabbitMQ 发送。 */
    @Test
    void dispatchByIdDoesNotPublishWhenClaimLost() {
        when(repository.claimByEventId(eq("event-1"), any(), eq(Duration.ofSeconds(30)), eq(20))).thenReturn(null);
        when(repository.markExhaustedIfEligible("event-1", 20)).thenReturn(false);

        dispatcher.dispatchByEventId("event-1");

        verify(publisher, never()).publish(any());
        verify(metrics).recordOutboxClaim("skipped");
    }

    /** 扫描仅发现 ID，再逐条走相同分发入口；候选读取不提前领取。 */
    @Test
    void scanDispatchesCandidateIdsAndConvergesExhaustedSeparately() {
        when(repository.findClaimableEventIds(10, 20)).thenReturn(List.of("event-1"));
        when(repository.claimByEventId(eq("event-1"), any(), eq(Duration.ofSeconds(30)), eq(20))).thenReturn(null);
        when(repository.markExhaustedIfEligible("event-1", 20)).thenReturn(false);
        when(repository.findExhaustedEventIds(10, 20)).thenReturn(List.of("event-2"));
        when(repository.markExhaustedIfEligible("event-2", 20)).thenReturn(true);

        dispatcher.dispatchScanBatch();

        verify(repository).findClaimableEventIds(10, 20);
        verify(repository).findExhaustedEventIds(10, 20);
        verify(metrics).recordOutboxAttemptsExhausted();
        verify(metrics).recordOutboxScan(any(Long.class));
    }
}
