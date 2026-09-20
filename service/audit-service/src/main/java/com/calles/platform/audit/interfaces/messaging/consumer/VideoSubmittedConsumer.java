package com.calles.platform.audit.interfaces.messaging.consumer;

import com.calles.platform.audit.application.coordinator.AuditTaskCoordinator;
import com.calles.platform.audit.config.AuditMessagingConfiguration;
import com.calles.platform.audit.interfaces.messaging.event.VideoSubmittedMessage;
import com.calles.platform.audit.interfaces.messaging.event.VideoSubmittedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 视频提审事件 (content.video.submitted) RabbitMQ 消费者 (VideoSubmittedConsumer)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：消息接口层消费者，监听 RabbitMQ 中的视频提审事件；</li>
 *   <li><b>消息路由</b>：交换机 {@code media.platform.events}，队列 {@code audit-service.content-video-submitted.v1}，路由键 {@code content.video.submitted}；</li>
 *   <li><b>协作对象</b>：反序列化绑定 {@link VideoSubmittedMessage} 后，委托 {@link AuditTaskCoordinator} 启动机审流水线；</li>
 *   <li><b>强类型绑定</b>：基于强类型数据模型一次性反序列化，彻底消除弱类型 {@code JsonNode} 遍历，支持标准信封与扁平载荷容错。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class VideoSubmittedConsumer {

    /** 审核业务协调编排器。 */
    private final AuditTaskCoordinator auditTaskCoordinator;

    /** JSON 强类型反序列化工具。 */
    private final ObjectMapper objectMapper;

    public VideoSubmittedConsumer(AuditTaskCoordinator auditTaskCoordinator, ObjectMapper objectMapper) {
        this.auditTaskCoordinator = auditTaskCoordinator;
        this.objectMapper = objectMapper;
    }

    /**
     * 监听并消费提审事件，启动审核流水线。
     *
     * @param message 接收到的 AMQP 原始消息实体
     */
    @RabbitListener(queues = AuditMessagingConfiguration.AUDIT_QUEUE)
    public void onVideoSubmitted(Message message) {
        // 步骤 1：记录消息长度日志（避免直接打印消息体包含敏感信息）
        int bodyLength = message.getBody() != null ? message.getBody().length : 0;
        log.info("收到视频提审事件消息: 消息大小=[{}] 字节", bodyLength);

        try {
            // 步骤 2：直接通过强类型模型反序列化消息体，消除 JsonNode 弱类型解析
            VideoSubmittedMessage eventMessage = objectMapper.readValue(message.getBody(), VideoSubmittedMessage.class);
            if (eventMessage == null) {
                log.warn("视频提审事件反序列化结果为空，拒绝消费");
                return;
            }

            // 步骤 3：提取并绑定 MDC traceId
            String traceId = eventMessage.traceId();
            if (traceId != null && !traceId.isBlank()) {
                MDC.put("traceId", traceId);
            }

            // 步骤 4：提取规范化提审业务载荷
            VideoSubmittedPayload payload = eventMessage.resolvePayload();

            // 步骤 5：关键业务属性非空守卫校验
            if (payload == null || payload.videoId() == null || payload.videoId().isBlank()) {
                log.error("提审事件消息缺失关键字段 videoId，拒绝消费");
                return;
            }

            // 步骤 6：委托业务协调器启动自动化机审流水线
            auditTaskCoordinator.processVideoSubmission(
                    payload.videoId(),
                    payload.vid(),
                    payload.authorId(),
                    payload.title(),
                    payload.description(),
                    payload.coverFileId(),
                    payload.videoFileId(),
                    payload.duration()
            );
        } catch (Exception e) {
            // 步骤 7：消费异常记录并向上抛出，交由 RabbitMQ 重试或转入死信队列 (DLQ)
            log.error("处理视频提审事件消息发生异常: {}", e.getMessage(), e);
            throw new RuntimeException("提审事件消费处理失败，触发重试", e);
        } finally {
            // 步骤 8：清理 MDC 日志追踪上下文
            MDC.remove("traceId");
        }
    }
}
