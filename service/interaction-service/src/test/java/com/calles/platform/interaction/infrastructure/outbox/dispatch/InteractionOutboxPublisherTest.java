package com.calles.platform.interaction.infrastructure.outbox.dispatch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.config.InteractionMessagingConfiguration;
import com.calles.platform.interaction.config.InteractionOutboxProperties;
import com.calles.platform.interaction.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxRepository;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class InteractionOutboxPublisherTest {

    @Mock
    private InteractionOutboxRepository repository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private InteractionOutboxProperties properties;
    private InteractionOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new InteractionOutboxProperties();
        publisher = new InteractionOutboxPublisher(repository, rabbitTemplate, properties);
    }

    @Test
    @DisplayName("RabbitMQ 发送成功并收到 Broker Confirm 后标记发布完成")
    void shouldMarkPublishedOnSuccessfulConfirm() {
        ClaimedOutboxMessage message = new ClaimedOutboxMessage(
                "evt_01", "vid_100", "interaction.video-action", 1,
                "{}", "trace_01", Instant.now(), 1, "token_123"
        );

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                eq(InteractionMessagingConfiguration.MEDIA_EVENTS_EXCHANGE),
                eq(InteractionMessagingConfiguration.VIDEO_ACTION_ROUTING_KEY),
                any(),
                any(CorrelationData.class)
        );

        when(repository.markPublished(message)).thenReturn(true);

        publisher.publish(message);

        verify(repository).markPublished(message);
    }

    @Test
    @DisplayName("RabbitMQ 发生异常时回写失败退避状态并保持原 eventId")
    void shouldMarkFailedOnException() {
        ClaimedOutboxMessage message = new ClaimedOutboxMessage(
                "evt_01", "vid_100", "interaction.video-action", 1,
                "{}", "trace_01", Instant.now(), 1, "token_123"
        );

        doAnswer(invocation -> {
            throw new RuntimeException("Broker network failure");
        }).when(rabbitTemplate).send(
                eq(InteractionMessagingConfiguration.MEDIA_EVENTS_EXCHANGE),
                eq(InteractionMessagingConfiguration.VIDEO_ACTION_ROUTING_KEY),
                any(),
                any(CorrelationData.class)
        );

        publisher.publish(message);

        verify(repository).markFailed(eq(message), eq(properties.getMaxAttempts()), anyString());
    }
}
