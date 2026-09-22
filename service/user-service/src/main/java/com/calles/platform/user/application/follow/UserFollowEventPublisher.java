package com.calles.platform.user.application.follow;

import com.calles.platform.common.core.event.EventEnvelope;
import com.calles.platform.user.domain.event.UserFollowedPayload;
import com.calles.platform.user.domain.event.UserUnfollowedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

/**
 * 关注领域事件发布器，负责将关注/取关行为组装为平台标准 {@link EventEnvelope} 投递至 RabbitMQ。
 * 具备事务感知能力，确保本地数据库事务成功提交（afterCommit）后才触发外部广播。
 */
@Component
public class UserFollowEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserFollowEventPublisher.class);

    /** 平台统一事件 Topic 交换机。 */
    private static final String PLATFORM_EXCHANGE = "media.platform.events";

    /** 关注建立领域事件 RoutingKey。 */
    public static final String ROUTING_KEY_FOLLOWED = "user.relation.followed.v1";

    /** 关注取消领域事件 RoutingKey。 */
    public static final String ROUTING_KEY_UNFOLLOWED = "user.relation.unfollowed.v1";

    private final RabbitTemplate rabbitTemplate;

    public UserFollowEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 事务提交后异步发布关注事件。
     *
     * @param userId 发起关注用户ID
     * @param targetUserId 被关注用户ID
     */
    public void publishFollowedEvent(String userId, String targetUserId) {
        runAfterCommit(() -> {
            String eventId = UUID.randomUUID().toString();
            String traceId = resolveCurrentTraceId();
            String now = Instant.now().toString();
            UserFollowedPayload payload = new UserFollowedPayload(userId, targetUserId, now);

            EventEnvelope<UserFollowedPayload> envelope = new EventEnvelope<>(
                    eventId,
                    "user.relation.followed",
                    1,
                    now,
                    "user-service",
                    userId,
                    traceId,
                    payload
            );

            try {
                rabbitTemplate.convertAndSend(PLATFORM_EXCHANGE, ROUTING_KEY_FOLLOWED, envelope);
                LOGGER.info("已发布用户关注事件: eventId={}, userId={}, targetUserId={}, traceId={}",
                        eventId, userId, targetUserId, traceId);
            } catch (Exception ex) {
                LOGGER.error("发布用户关注事件失败: eventId={}, userId={}, targetUserId={}",
                        eventId, userId, targetUserId, ex);
            }
        });
    }

    /**
     * 事务提交后异步发布取关事件。
     *
     * @param userId 发起取消关注用户ID
     * @param targetUserId 被取消关注用户ID
     */
    public void publishUnfollowedEvent(String userId, String targetUserId) {
        runAfterCommit(() -> {
            String eventId = UUID.randomUUID().toString();
            String traceId = resolveCurrentTraceId();
            String now = Instant.now().toString();
            UserUnfollowedPayload payload = new UserUnfollowedPayload(userId, targetUserId, now);

            EventEnvelope<UserUnfollowedPayload> envelope = new EventEnvelope<>(
                    eventId,
                    "user.relation.unfollowed",
                    1,
                    now,
                    "user-service",
                    userId,
                    traceId,
                    payload
            );

            try {
                rabbitTemplate.convertAndSend(PLATFORM_EXCHANGE, ROUTING_KEY_UNFOLLOWED, envelope);
                LOGGER.info("已发布用户取关事件: eventId={}, userId={}, targetUserId={}, traceId={}",
                        eventId, userId, targetUserId, traceId);
            } catch (Exception ex) {
                LOGGER.error("发布用户取关事件失败: eventId={}, userId={}, targetUserId={}",
                        eventId, userId, targetUserId, ex);
            }
        });
    }

    /**
     * 在当前事务提交后执行，若无事务则直接同步触发。
     */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String resolveCurrentTraceId() {
        String traceId = MDC.get("traceId");
        return (traceId == null || traceId.isBlank()) ? UUID.randomUUID().toString() : traceId;
    }
}
