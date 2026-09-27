package com.calles.platform.interaction.interfaces.messaging.consumer;

import com.calles.platform.interaction.application.video.VideoMetadataApplicationService;
import com.calles.platform.interaction.config.InteractionMessagingConfiguration;
import com.calles.platform.interaction.interfaces.messaging.event.VideoMetadataMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 视频元数据事件 (content.video.metadata) RabbitMQ 消费者。
 *
 * <p>职责与执行策略：
 * <ul>
 *   <li><b>所属边界</b>：interaction-service 消息入站适配层，只做解析、追踪上下文绑定与委托；</li>
 *   <li><b>监听队列</b>：{@link InteractionMessagingConfiguration#VIDEO_METADATA_QUEUE}；</li>
 *   <li><b>失败语义</b>：反序列化失败等不可重试的消息直接丢弃并告警；业务异常向上抛出交由容器重试，
 *       重试耗尽后由队列死信配置转入死信队列；</li>
 *   <li><b>降级约定</b>：快照缺失或非法时心跳仍可保存断点，只是不产生播放量与完播事件；</li>
 *   <li><b>双重防御</b>：优先使用消息体解析所得的 eventId/traceId，当且仅当载荷缺失时读取 AMQP 报头兜底回填。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoMetadataConsumer {

    private final VideoMetadataApplicationService videoMetadataApplicationService;
    private final ObjectMapper objectMapper;

    /**
     * 监听视频元数据领域事件。
     *
     * @param payload 原始 JSON 字符串载荷
     * @param amqpMessageId AMQP 传输层消息唯一 ID (若存在)
     * @param amqpTraceId AMQP 传输层链路追踪 ID (若存在)
     */
    @RabbitListener(queues = InteractionMessagingConfiguration.VIDEO_METADATA_QUEUE)
    public void onVideoMetadata(
            String payload,
            @Header(value = AmqpHeaders.MESSAGE_ID, required = false) String amqpMessageId,
            @Header(value = "traceId", required = false) String amqpTraceId) {
        // 步骤 1：反序列化强类型消息对象；解析失败属于不可恢复消息，直接丢弃避免无限重试
        VideoMetadataMessage message;
        try {
            message = objectMapper.readValue(payload, VideoMetadataMessage.class);
        } catch (Exception e) {
            log.error("反序列化视频元数据事件失败，丢弃非法消息: payload={}, error={}", payload, e.getMessage(), e);
            return;
        }

        if (message == null) {
            log.warn("视频元数据事件反序列化结果为空，丢弃消息");
            return;
        }

        // 步骤 2：传输层报头兜底防御：消息体已有标识时保持不变，否则使用 AMQP Header 补全
        message = message.withFallbackIdentifiers(amqpMessageId, amqpTraceId);

        // 步骤 3：绑定 traceId 全链路追踪上下文，缺失时回退事件 ID
        String traceId = message.traceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = message.eventId();
        }
        try {
            if (traceId != null && !traceId.isBlank()) {
                MDC.put("traceId", traceId);
            }

            // 步骤 4：委托应用服务幂等写入本地时长快照
            VideoMetadataApplicationService.SnapshotApplyResult result =
                    videoMetadataApplicationService.apply(message);
            log.info("视频元数据事件处理完成: eventId={}, vid={}, result={}",
                    message.eventId(), message.vid(), result);
        } catch (Exception e) {
            log.error("处理视频元数据事件失败，交由容器重试: eventId={}, vid={}, error={}",
                    message.eventId(), message.vid(), e.getMessage(), e);
            throw new IllegalStateException("视频元数据事件处理失败: eventId=" + message.eventId(), e);
        } finally {
            MDC.remove("traceId");
        }
    }
}
