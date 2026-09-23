package com.calles.platform.user.infrastructure.outbox.dispatch;

import com.calles.platform.user.config.UserOutboxProperties;
import com.calles.platform.user.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.user.infrastructure.outbox.persistence.UserOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserOutboxPublisher 消息投递测试")
class UserOutboxPublisherTest {

    @Mock
    private UserOutboxRepository repository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private UserOutboxProperties properties;
    private UserOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new UserOutboxProperties();
        properties.setConfirmTimeout(Duration.ofSeconds(2));
        publisher = new UserOutboxPublisher(repository, rabbitTemplate, properties);
    }

    @Test
    @DisplayName("投递成功并收到 Broker ACK 回写 PUBLISHED")
    void testPublishSuccess() {
        ClaimedOutboxMessage claimed = new ClaimedOutboxMessage(
                "evt-101", "user_1", "interaction.author-action", 1,
                "{\"eventId\":\"evt-101\"}", "trace-abc", Instant.now(), 1, "token-1"
        );

        completeConfirm(true, null);
        when(repository.markPublished(claimed)).thenReturn(true);

        publisher.publish(claimed);

        verify(repository).markPublished(claimed);
    }

    private void completeConfirm(boolean ack, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).send(eq(UserOutboxPublisher.MEDIA_EVENTS_EXCHANGE),
                eq(UserOutboxPublisher.AUTHOR_ACTION_ROUTING_KEY), any(Message.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("投递发生异常时调用 repository.markFailed")
    void testPublishException() {
        ClaimedOutboxMessage claimed = new ClaimedOutboxMessage(
                "evt-101", "user_1", "interaction.author-action", 1,
                "{\"eventId\":\"evt-101\"}", "trace-abc", Instant.now(), 1, "token-1"
        );

        doThrow(new RuntimeException("Rabbit connection dropped"))
                .when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        publisher.publish(claimed);

        verify(repository).markFailed(eq(claimed), eq(properties.getMaxAttempts()), eq("RuntimeException"));
    }
}
