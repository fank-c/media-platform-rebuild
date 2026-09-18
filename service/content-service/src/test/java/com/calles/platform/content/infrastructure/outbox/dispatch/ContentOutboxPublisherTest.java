package com.calles.platform.content.infrastructure.outbox.dispatch;

import com.calles.platform.content.config.ContentMessagingConfiguration;
import com.calles.platform.content.config.ContentOutboxProperties;
import com.calles.platform.content.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.content.infrastructure.outbox.persistence.ContentOutboxRepository;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContentOutboxPublisher 统一单条发布器单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentOutboxPublisher 消息发布器单元测试")
class ContentOutboxPublisherTest {

    @Mock
    private ContentOutboxRepository repository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ContentOutboxProperties properties;
    private ContentOutboxPublisher publisher;
    private ClaimedOutboxMessage claimed;

    @BeforeEach
    void setUp() {
        properties = new ContentOutboxProperties();
        properties.setMaxAttempts(20);
        properties.setConfirmTimeout(Duration.ofSeconds(1));
        publisher = new ContentOutboxPublisher(repository, rabbitTemplate, properties);
        claimed = new ClaimedOutboxMessage(
                "event-100",
                "content.video.submitted",
                "{\"videoId\":\"v-100\"}",
                "trace-100",
                "token-abc",
                1
        );
    }

    @Test
    @DisplayName("Broker 返回 ACK 时更新发件箱记录为 PUBLISHED")
    void brokerAckMarksMessagePublished() {
        completeConfirm(true, null);
        when(repository.markPublished(claimed)).thenReturn(true);

        publisher.publish(claimed);

        verify(repository).markPublished(claimed);
    }

    @Test
    @DisplayName("Broker 返回 NACK 时触发失败回写并记录重试退避")
    void brokerNackMarksAttemptFailed() {
        completeConfirm(false, "broker-nack");
        when(repository.markFailed(claimed, 20, "IllegalStateException")).thenReturn(true);

        publisher.publish(claimed);

        verify(repository).markFailed(claimed, 20, "IllegalStateException");
    }

    @Test
    @DisplayName("投递成功但租约令牌已失效时安全退出且不抛异常")
    void lostClaimAfterConfirmHandlesGracefully() {
        completeConfirm(true, null);
        when(repository.markPublished(claimed)).thenReturn(false);

        publisher.publish(claimed);

        verify(repository).markPublished(claimed);
    }

    private void completeConfirm(boolean ack, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).send(eq(ContentMessagingConfiguration.MEDIA_EVENTS_EXCHANGE),
                eq("content.video.submitted"), any(Message.class), any(CorrelationData.class));
    }
}
