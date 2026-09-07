package com.calles.platform.auth.infrastructure.messaging;

import com.calles.platform.auth.domain.account.AuthAccount;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRecord;
import com.calles.platform.common.core.event.EventEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 构造认证账户创建事件，固定 v1 信封并避免向消息泄露登录名和凭据。
 *
 * <p>该组件只负责事件序列化，不负责数据库事务或 RabbitMQ 投递。</p>
 */
@Component
public class AccountCreatedEventFactory {

    /** JSON 序列化器，复用 Spring 配置以保持时间和未知字段策略一致。 */
    private final ObjectMapper objectMapper;

    /** 可替换时钟，保证事件时间在测试中可控。 */
    private final Clock clock;

    /**
     * 创建事件工厂。
     *
     * @param objectMapper JSON 序列化器
     * @param authClock 认证服务时钟
     */
    public AccountCreatedEventFactory(ObjectMapper objectMapper, Clock authClock) {
        this.objectMapper = objectMapper;
        this.clock = authClock;
    }

    /**
     * 为已持久化的普通账户创建稳定事件记录。
     *
     * @param account 已生成 ID 的认证账户
     * @return 可与账号同事务写入的 Outbox 记录
     * @throws IllegalStateException 序列化失败时阻止注册事务提交
     */
    public AuthOutboxRecord create(AuthAccount account) {
        // 事件仅承载 user-service 初始化所需的最小事实，避免传播登录名等认证数据。
        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = clock.instant();
        String traceId = resolveTraceId(eventId);
        AccountCreatedPayloadV1 payload = new AccountCreatedPayloadV1(account.getId(), "user",
                format(account.getCreatedAt() == null
                        ? occurredAt : account.getCreatedAt().toInstant(ZoneOffset.UTC)));
        EventEnvelope<AccountCreatedPayloadV1> envelope = new EventEnvelope<>(eventId,
                "auth.account.created", 1, format(occurredAt), "auth-service", account.getId(),
                traceId, payload);
        try {
            return new AuthOutboxRecord(eventId, account.getId(), "auth.account.created", 1,
                    objectMapper.writeValueAsString(envelope), traceId, occurredAt);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("账号创建事件序列化失败", exception);
        }
    }

    /** 将时间统一输出为 UTC ISO-8601，避免服务默认时区影响事件契约。 */
    private String format(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    /**
     * 优先复用当前请求链路；没有可用链路时使用 eventId，确保 Outbox 重试始终携带稳定追踪标识。
     *
     * @param eventId 当前事件的稳定唯一标识
     * @return 非空追踪标识
     */
    private String resolveTraceId(String eventId) {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? eventId : traceId;
    }
}
