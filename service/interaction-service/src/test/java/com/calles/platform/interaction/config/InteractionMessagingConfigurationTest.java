package com.calles.platform.interaction.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 互动服务 RabbitMQ 消息拓扑配置单元测试。
 *
 * <p>重点验证命名与路由约定：事件路由键等于事件类型（不带版本后缀），
 * 视频元数据订阅队列与死信队列名称不重复。</p>
 */
class InteractionMessagingConfigurationTest {

    @Test
    @DisplayName("事件类型不带版本后缀，派发时直接作为路由键使用")
    void shouldExposeUnversionedEventType() {
        assertThat(InteractionEventPublisher.EVENT_TYPE_VIDEO_ACTION)
                .isEqualTo("interaction.video-action");
        assertThat(InteractionEventPublisher.EVENT_VERSION).isEqualTo(1);
    }

    @Test
    @DisplayName("视频元数据订阅队列与路由键符合跨服务契约命名")
    void shouldDeclareVideoMetadataTopology() {
        assertThat(InteractionMessagingConfiguration.VIDEO_METADATA_ROUTING_KEY)
                .isEqualTo("content.video.metadata");
        assertThat(InteractionMessagingConfiguration.VIDEO_METADATA_QUEUE)
                .isEqualTo("interaction-service.video-metadata");
        assertThat(InteractionMessagingConfiguration.VIDEO_METADATA_DLQ)
                .isNotEqualTo(InteractionMessagingConfiguration.VIDEO_METADATA_QUEUE);
        assertThat(InteractionMessagingConfiguration.VIDEO_METADATA_DLX)
                .isNotEqualTo(InteractionMessagingConfiguration.MEDIA_EVENTS_EXCHANGE);
    }
}
