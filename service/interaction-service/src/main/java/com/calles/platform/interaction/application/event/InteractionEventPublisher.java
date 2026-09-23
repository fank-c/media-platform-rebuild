package com.calles.platform.interaction.application.event;

import com.calles.platform.interaction.application.outbox.InteractionOutboxDispatchNotifier;
import com.calles.platform.interaction.config.InteractionOutboxProperties;
import com.calles.platform.interaction.domain.model.event.InteractionEventEnvelope;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.infrastructure.outbox.model.InteractionOutboxRecord;
import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 视频互动领域事件发布器，统一采用 Transactional Outbox 事务发件箱模式。
 *
 * <p>核心机制：
 * <ul>
 *   <li><b>本地事务一致性</b>：在业务事务内将事件序列化并写入 {@code interaction_outbox} 表，与业务事实强一致；</li>
 *   <li><b>统一契约</b>：统一输出契约 {@code interaction.video-action} v1 规范载荷；</li>
 *   <li><b>受控派发</b>：根据配置开关受控派发或仅存储，确保与下游推荐模块平滑演进。</li>
 * </ul>
 * </p>
 */
@Component
public class InteractionEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionEventPublisher.class);

    public static final String EVENT_TYPE_VIDEO_ACTION = "interaction.video-action";

    private final InteractionOutboxRepository outboxRepository;
    private final InteractionOutboxDispatchNotifier dispatchNotifier;
    private final InteractionOutboxProperties outboxProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InteractionEventPublisher(InteractionOutboxRepository outboxRepository,
                                     InteractionOutboxDispatchNotifier dispatchNotifier,
                                     InteractionOutboxProperties outboxProperties,
                                     ObjectMapper objectMapper,
                                     Clock clock) {
        this.outboxRepository = outboxRepository;
        this.dispatchNotifier = dispatchNotifier;
        this.outboxProperties = outboxProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在当前业务事务内记录一条视频互动领域事件至发件箱。
     *
     * @param payload 视频交互载荷
     */
    public void publishVideoAction(VideoActionPayload payload) {
        if (!outboxProperties.isEnabled()) {
            LOGGER.debug("Interaction Outbox 总开关未开启，跳过事件生成");
            return;
        }

        // 步骤 1: 生成全局唯一事件 ID 与链路追踪 ID
        String eventId = UUID.randomUUID().toString().replace("-", "");
        String traceId = resolveCurrentTraceId();
        Instant now = clock.instant();
        String occurredAtStr = DateTimeFormatter.ISO_INSTANT.format(now);

        // 步骤 2: 组装统一契约信封
        InteractionEventEnvelope<VideoActionPayload> envelope = new InteractionEventEnvelope<>(
                eventId,
                EVENT_TYPE_VIDEO_ACTION,
                1,
                traceId,
                occurredAtStr,
                payload
        );

        // 步骤 3: 序列化为 JSON 载荷
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("视频互动事件 JSON 序列化失败: eventId=" + eventId, e);
        }

        // 步骤 4: 构造持久化发件箱记录 (以 vid 作为关联聚合根 ID)
        InteractionOutboxRecord record = new InteractionOutboxRecord(
                eventId,
                payload.vid(),
                EVENT_TYPE_VIDEO_ACTION,
                1,
                json,
                traceId,
                now
        );

        // 步骤 5: 原子写入发件箱表，并触发派发通知
        outboxRepository.insert(record);
        dispatchNotifier.notifyDispatch(eventId);

        LOGGER.info("已记录视频互动事件至 Outbox: eventId={}, vid={}, userId={}, action={}, state={}",
                eventId, payload.vid(), payload.userId(), payload.action(), payload.state());
    }

    private String resolveCurrentTraceId() {
        String traceId = MDC.get("traceId");
        return (traceId == null || traceId.isBlank()) ? UUID.randomUUID().toString() : traceId;
    }
}
