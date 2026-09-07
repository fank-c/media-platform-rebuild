package com.calles.platform.auth.infrastructure.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.auth.config.AuthOutboxProperties;
import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Auth Outbox 统一单条发送器的确认与 token 条件回写测试。 */
@ExtendWith(MockitoExtension.class)
class AuthOutboxPublisherTest {

    @Mock private AuthOutboxRepository repository;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private AuthOperationalMetrics metrics;
    private AuthOutboxPublisher publisher;
    private ClaimedOutboxMessage claimed;

    /** 为每个场景创建固定领取快照。 */
    @BeforeEach
    void setUp() {
        AuthOutboxProperties properties = new AuthOutboxProperties();
        properties.setMaxAttempts(20);
        properties.setConfirmTimeout(Duration.ofSeconds(1));
        publisher = new AuthOutboxPublisher(repository, rabbitTemplate, metrics, properties);
        claimed = new ClaimedOutboxMessage("550e8400-e29b-41d4-a716-446655440000", "{}", "claim", 1);
    }

    /** Broker ack 且消息未退回时才将当前 token 的记录标记为已发布。 */
    @Test
    void brokerAckMarksMessagePublished() {
        completeConfirm(true, null);
        when(repository.markPublished(claimed)).thenReturn(true);

        publisher.publish(claimed);

        verify(repository).markPublished(claimed);
        verify(metrics).recordOutboxPublished();
    }

    /** Broker nack 必须以当前 token 进入退避，不能误标成功。 */
    @Test
    void brokerNackMarksAttemptFailed() {
        completeConfirm(false, "broker-nack");
        when(repository.markFailed(claimed, 20, "IllegalStateException")).thenReturn(true);

        publisher.publish(claimed);

        verify(repository).markFailed(claimed, 20, "IllegalStateException");
        verify(metrics).recordOutboxFailed();
    }

    /** 成功确认后若 token 已失效，旧领取者只能记录 lost，不能覆盖新状态。 */
    @Test
    void lostClaimAfterConfirmDoesNotRecordPublished() {
        completeConfirm(true, null);
        when(repository.markPublished(claimed)).thenReturn(false);

        publisher.publish(claimed);

        verify(metrics).recordOutboxClaim("lost");
    }

    /** 配置 RabbitTemplate mock 在发送时完成当前消息的发布确认 Future。 */
    private void completeConfirm(boolean ack, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).send(eq("media.platform.events"), eq("auth.account.created.v1"),
                any(Message.class), any(CorrelationData.class));
    }
}
