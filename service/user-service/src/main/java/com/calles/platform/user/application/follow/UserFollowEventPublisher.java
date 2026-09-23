package com.calles.platform.user.application.follow;

import com.calles.platform.user.application.outbox.UserOutboxDispatchNotifier;
import com.calles.platform.user.domain.follow.event.AuthorActionPayload;
import com.calles.platform.user.domain.follow.event.InteractionEventEnvelope;
import com.calles.platform.user.infrastructure.outbox.model.UserOutboxRecord;
import com.calles.platform.user.infrastructure.outbox.persistence.UserOutboxRepository;
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
 * 关注领域事件发布器，统一采用 Transactional Outbox 事务发件箱模式。
 *
 * <p>核心机制：
 * <ul>
 *   <li><b>本地事务一致性</b>：在业务事务内将事件序列化并写入 {@code user_outbox} 表，与拓扑变更强一致；</li>
 *   <li><b>契约统一</b>：输出符合推荐互动流标准模板的 {@code interaction.author-action} 规范载荷；</li>
 *   <li><b>双轨派发</b>：事务提交后触发毫秒级快速内存通知，结合后台定时扫描兜底保证 100% 不丢事件。</li>
 * </ul>
 * </p>
 */
@Component
public class UserFollowEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserFollowEventPublisher.class);

    public static final String EVENT_TYPE_AUTHOR_ACTION = "interaction.author-action";

    private final UserOutboxRepository outboxRepository;
    private final UserOutboxDispatchNotifier dispatchNotifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public UserFollowEventPublisher(UserOutboxRepository outboxRepository,
                                    UserOutboxDispatchNotifier dispatchNotifier,
                                    ObjectMapper objectMapper,
                                    Clock clock) {
        this.outboxRepository = outboxRepository;
        this.dispatchNotifier = dispatchNotifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 记录并发布用户关注事件。
     *
     * @param userId 关注发起人账号 ID
     * @param targetUserId 被关注目标创作者账号 ID
     */
    public void publishFollowedEvent(String userId, String targetUserId) {
        AuthorActionPayload payload = AuthorActionPayload.follow(userId, targetUserId);
        recordOutboxAndNotify(userId, payload);
    }

    /**
     * 记录并发布用户取消关注事件。
     *
     * @param userId 取消关注发起人账号 ID
     * @param targetUserId 被取消关注目标创作者账号 ID
     */
    public void publishUnfollowedEvent(String userId, String targetUserId) {
        AuthorActionPayload payload = AuthorActionPayload.unfollow(userId, targetUserId);
        recordOutboxAndNotify(userId, payload);
    }

    /**
     * 组装统一推荐交互模板信封，写入 Outbox 并注册事务后快速派发。
     */
    private void recordOutboxAndNotify(String userId, AuthorActionPayload payload) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        String traceId = resolveCurrentTraceId();
        Instant now = clock.instant();
        String occurredAtStr = DateTimeFormatter.ISO_INSTANT.format(now);

        InteractionEventEnvelope<AuthorActionPayload> envelope = new InteractionEventEnvelope<>(
                eventId,
                EVENT_TYPE_AUTHOR_ACTION,
                1,
                traceId,
                occurredAtStr,
                payload
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("关注互动事件 JSON 序列化失败: eventId=" + eventId, e);
        }

        UserOutboxRecord record = new UserOutboxRecord(
                eventId,
                userId,
                EVENT_TYPE_AUTHOR_ACTION,
                1,
                json,
                traceId,
                now
        );

        outboxRepository.insert(record);
        dispatchNotifier.notifyDispatch(eventId);

        LOGGER.info("已记录关注互动事件至 Outbox: eventId={}, userId={}, authorId={}, action={}, state={}",
                eventId, userId, payload.authorId(), payload.action(), payload.state());
    }

    private String resolveCurrentTraceId() {
        String traceId = MDC.get("traceId");
        return (traceId == null || traceId.isBlank()) ? UUID.randomUUID().toString() : traceId;
    }
}
