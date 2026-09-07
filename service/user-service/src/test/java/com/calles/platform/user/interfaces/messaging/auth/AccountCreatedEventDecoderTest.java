package com.calles.platform.user.interfaces.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.calles.platform.common.core.event.EventEnvelope;
import com.calles.platform.user.application.event.AccountCreatedPayloadV1;
import com.calles.platform.user.exception.InvalidAccountCreatedEventException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 账号创建 v1 消息 Decoder 的兼容边界测试。 */
class AccountCreatedEventDecoderTest {

    private static final String EVENT_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String ACCOUNT_ID = "0123456789abcdef0123456789abcdef";

    /** 旧消费者的 version asInt(-1) 行为必须在类型化改造后保持一致。 */
    @Test
    void preservesLegacyVersionCoercionBoundary() {
        AccountCreatedEventDecoder decoder = new AccountCreatedEventDecoder(new ObjectMapper());
        List<String> acceptedVersions = List.of("1", "\"1\"", "true", "1.9", "\"1.9\"");

        for (String version : acceptedVersions) {
            EventEnvelope<AccountCreatedPayloadV1> decoded = decoder.decode(eventJson(version, "valid-trace", ""));
            assertEquals(1, decoded.version(), "应保留旧 version 输入: " + version);
        }
        for (String version : List.of("null", "{}", "[]", "2")) {
            assertThrows(InvalidAccountCreatedEventException.class,
                    () -> decoder.decode(eventJson(version, "valid-trace", "")),
                    "应拒绝不支持 version 输入: " + version);
        }
    }

    /** 必需标识字段必须真实为 JSON 字符串，不能由普通 POJO 绑定隐式转换。 */
    @Test
    void rejectsNonTextualRequiredField() {
        AccountCreatedEventDecoder decoder = new AccountCreatedEventDecoder(new ObjectMapper());
        byte[] body = ("""
                {"eventId":123,"eventType":"auth.account.created","version":1,
                 "aggregateId":"%s","payload":{"accountId":"%s","accountType":"user"}}
                """).formatted(ACCOUNT_ID, ACCOUNT_ID).getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidAccountCreatedEventException.class, () -> decoder.decode(body));
    }

    /** 未知字段及旧版未校验字段不应改变 v1 处理结果，合法 traceId 应被单独提取。 */
    @Test
    void acceptsUnknownFieldsAndDoesNotRequireProducerOrTimeFields() {
        AccountCreatedEventDecoder decoder = new AccountCreatedEventDecoder(new ObjectMapper());

        EventEnvelope<AccountCreatedPayloadV1> decoded = decoder.decode(eventJson("1", "trace-001", ",\"unknown\":true"));

        assertEquals(EVENT_ID, decoded.eventId());
        assertEquals(ACCOUNT_ID, decoded.payload().accountId());
        assertEquals("trace-001", decoder.resolveTraceId(decoded));
    }

    /** 非法 traceId 只影响日志追踪，不能把本来合法的业务事件拒绝到死信。 */
    @Test
    void invalidTraceIdFallsBackWithoutRejectingEvent() {
        AccountCreatedEventDecoder decoder = new AccountCreatedEventDecoder(new ObjectMapper());

        EventEnvelope<AccountCreatedPayloadV1> decoded = decoder.decode(eventJson("1", "bad trace id", ""));

        assertNull(decoder.resolveTraceId(decoded));
    }

    /** 未参与资料初始化的兼容字段可为任意 JSON 类型，非文本 traceId 只会被日志追踪忽略。 */
    @Test
    void ignoresOptionalCompatibilityFieldsWithoutWeakeningRequiredTextFields() {
        AccountCreatedEventDecoder decoder = new AccountCreatedEventDecoder(new ObjectMapper());
        byte[] body = ("""
                {"eventId":"%s","eventType":"auth.account.created","version":1,
                 "occurredAt":{},"producer":[],"aggregateId":"%s","traceId":false,
                 "payload":{"accountId":"%s","accountType":"user","createdAt":{"future":true}}}
                """).formatted(EVENT_ID, ACCOUNT_ID, ACCOUNT_ID).getBytes(StandardCharsets.UTF_8);

        EventEnvelope<AccountCreatedPayloadV1> decoded = decoder.decode(body);

        assertEquals(ACCOUNT_ID, decoded.payload().accountId());
        assertNull(decoder.resolveTraceId(decoded));
    }

    /** 每个必需标识字段都必须是非空白 JSON 文本，避免对象绑定意外放宽 v1 契约。 */
    @Test
    void rejectsBlankOrNonTextualRequiredFields() {
        AccountCreatedEventDecoder decoder = new AccountCreatedEventDecoder(new ObjectMapper());
        List<byte[]> invalidBodies = List.of(
                ("{\"eventId\":123,\"eventType\":\"auth.account.created\",\"version\":1,"
                        + "\"aggregateId\":\"%s\",\"payload\":{\"accountId\":\"%s\",\"accountType\":\"user\"}}")
                        .formatted(ACCOUNT_ID, ACCOUNT_ID).getBytes(StandardCharsets.UTF_8),
                ("{\"eventId\":\"%s\",\"eventType\":false,\"version\":1,"
                        + "\"aggregateId\":\"%s\",\"payload\":{\"accountId\":\"%s\",\"accountType\":\"user\"}}")
                        .formatted(EVENT_ID, ACCOUNT_ID, ACCOUNT_ID).getBytes(StandardCharsets.UTF_8),
                ("{\"eventId\":\"%s\",\"eventType\":\"auth.account.created\",\"version\":1,"
                        + "\"aggregateId\":\" \",\"payload\":{\"accountId\":\"%s\",\"accountType\":\"user\"}}")
                        .formatted(EVENT_ID, ACCOUNT_ID).getBytes(StandardCharsets.UTF_8),
                ("{\"eventId\":\"%s\",\"eventType\":\"auth.account.created\",\"version\":1,"
                        + "\"aggregateId\":\"%s\",\"payload\":{\"accountId\":123,\"accountType\":\"user\"}}")
                        .formatted(EVENT_ID, ACCOUNT_ID).getBytes(StandardCharsets.UTF_8),
                ("{\"eventId\":\"%s\",\"eventType\":\"auth.account.created\",\"version\":1,"
                        + "\"aggregateId\":\"%s\",\"payload\":{\"accountId\":\"%s\",\"accountType\":\" \"}}")
                        .formatted(EVENT_ID, ACCOUNT_ID, ACCOUNT_ID).getBytes(StandardCharsets.UTF_8));

        for (byte[] invalidBody : invalidBodies) {
            assertThrows(InvalidAccountCreatedEventException.class, () -> decoder.decode(invalidBody));
        }
    }

    /** aggregateId 与 payload.accountId 不一致时不得初始化错误主体的资料。 */
    @Test
    void rejectsMismatchedAggregateAndPayloadAccount() {
        AccountCreatedEventDecoder decoder = new AccountCreatedEventDecoder(new ObjectMapper());
        byte[] body = ("""
                {"eventId":"%s","eventType":"auth.account.created","version":1,
                 "aggregateId":"%s","payload":{"accountId":"fedcba9876543210fedcba9876543210","accountType":"user"}}
                """).formatted(EVENT_ID, ACCOUNT_ID).getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidAccountCreatedEventException.class, () -> decoder.decode(body));
    }

    /** 构造仅含旧处理器实际读取字段的事件，防止测试错误地强化未约束字段。 */
    private byte[] eventJson(String version, String traceId, String trailingRootFields) {
        String json = """
                {"eventId":"%s","eventType":"auth.account.created","version":%s,
                 "aggregateId":"%s","traceId":"%s","payload":{"accountId":"%s","accountType":"user","futureField":true}%s}
                """.formatted(EVENT_ID, version, ACCOUNT_ID, traceId, ACCOUNT_ID, trailingRootFields);
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
