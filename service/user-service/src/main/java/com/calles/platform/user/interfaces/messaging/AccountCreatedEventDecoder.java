package com.calles.platform.user.interfaces.messaging;

import com.calles.platform.common.core.event.EventEnvelope;
import com.calles.platform.user.application.event.AccountCreatedPayloadV1;
import com.calles.platform.user.exception.InvalidAccountCreatedEventException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.NumberInput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code auth.account.created.v1} 的 RabbitMQ 入站协议解码器。
 *
 * <p>该组件只在协议边界把原始 JSON 绑定为本地类型化信封。它复制 Spring 的 JSON 配置后仅调整这份
 * Reader，既不影响 HTTP 的 ObjectMapper，也不让应用处理器依赖 JSON、AMQP 或认证服务类型。</p>
 */
@Component
public class AccountCreatedEventDecoder {

    /** 资料初始化支持的认证账户 ID 格式。 */
    private static final Pattern ACCOUNT_ID = Pattern.compile("^[0-9a-fA-F]{32}$");
    /** 可进入日志 MDC 的受限追踪标识格式，避免消息污染日志上下文。 */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** 固定泛型 Reader，保证 payload 绑定为本服务的 Record 而不是 Map。 */
    private final ObjectReader eventReader;

    /**
     * 基于 Spring JSON 配置创建仅供消息协议使用的类型化 Reader。
     *
     * @param objectMapper Spring 管理的 HTTP JSON 配置；不会被本类修改
     */
    public AccountCreatedEventDecoder(ObjectMapper objectMapper) {
        // 副本关闭普通标量到 String 的隐式转换，保留旧版“必需字段必须是真实 JSON 字符串”的拒绝语义。
        ObjectMapper protocolMapper = objectMapper.copy();
        protocolMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Array, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Object, CoercionAction.Fail);
        // 只为本协议安装兼容 mixin；公共信封和应用层载荷仍不引入 Jackson 注解。
        protocolMapper.addMixIn(EventEnvelope.class, EventEnvelopeMixin.class);
        protocolMapper.addMixIn(AccountCreatedPayloadV1.class, AccountCreatedPayloadMixin.class);
        this.eventReader = protocolMapper.readerFor(new TypeReference<EventEnvelope<AccountCreatedPayloadV1>>() {
        });
    }

    /**
     * 解析一次原始消息并校验当前支持的 v1 字段语义。
     *
     * <p>producer、occurredAt 与 payload.createdAt 延续既有行为：缺失或任意 JSON 类型都不作为
     * 拒收条件；根与载荷未知字段同样忽略。必需标识字段必须是非空白 JSON 字符串。</p>
     *
     * @param body RabbitMQ 消息原始字节，内容不得直接写入日志
     * @return 可直接交给应用层处理的类型化事件信封
     * @throws InvalidAccountCreatedEventException 消息不是支持的 v1 协议时抛出
     */
    public EventEnvelope<AccountCreatedPayloadV1> decode(byte[] body) {
        try {
            // Reader 一次完成对象绑定；version 与 traceId 的局部适配器仅保留历史宽松边界。
            EventEnvelope<AccountCreatedPayloadV1> event = eventReader.readValue(body);
            validate(event);
            return event;
        } catch (InvalidAccountCreatedEventException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidAccountCreatedEventException("账号创建事件格式不合法", exception);
        }
    }

    /**
     * 校验对象绑定后仍不能由类型系统表达的 v1 业务约束。
     *
     * @param event 已完成局部 JSON 绑定的事件信封
     */
    private void validate(EventEnvelope<AccountCreatedPayloadV1> event) {
        if (event == null) {
            throw new InvalidAccountCreatedEventException("账号创建事件不能为空");
        }
        String eventId = requiredText(event.eventId(), "eventId");
        UUID.fromString(eventId);
        String eventType = requiredText(event.eventType(), "eventType");
        String aggregateId = requiredText(event.aggregateId(), "aggregateId");
        AccountCreatedPayloadV1 payload = event.payload();
        String accountId = requiredText(payload == null ? null : payload.accountId(), "accountId");
        String accountType = requiredText(payload == null ? null : payload.accountType(), "accountType");

        // 仅接收当前 v1 普通用户事件，避免按已知格式猜测未知版本的业务含义。
        if (!"auth.account.created".equals(eventType) || event.version() != 1) {
            throw new InvalidAccountCreatedEventException("不支持的账号创建事件类型或版本");
        }
        if (!"user".equals(accountType)) {
            throw new InvalidAccountCreatedEventException("非普通用户主体不能初始化用户资料");
        }
        if (!aggregateId.equals(accountId) || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new InvalidAccountCreatedEventException("账号创建事件主体不一致或格式非法");
        }
    }

    /**
     * 校验必须以 JSON 文本绑定而来的非空字段。
     *
     * @param value 已绑定字段值
     * @param name 契约字段名
     * @return 已确认非空白的文本
     */
    private String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidAccountCreatedEventException("事件缺少字段: " + name);
        }
        return value;
    }

    /**
     * 从已类型化的信封取得可安全写入 MDC 的 traceId；追踪字段异常不影响业务接收。
     *
     * @param event 已校验的事件信封
     * @return 合法 traceId，或 {@code null}
     */
    public String resolveTraceId(EventEnvelope<AccountCreatedPayloadV1> event) {
        String traceId = event.traceId();
        return traceId != null && SAFE_TRACE_ID.matcher(traceId).matches() ? traceId : null;
    }

    /**
     * 仅供此 Reader 使用的公共信封绑定规则，忽略未参与当前消费的兼容字段和未知字段。
     */
    @JsonIgnoreProperties(value = {"occurredAt", "producer"}, ignoreUnknown = true)
    abstract static class EventEnvelopeMixin {

        /** 为本地协议 Reader 标注信封属性和两个历史兼容字段的适配器。 */
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        EventEnvelopeMixin(@JsonProperty("eventId") String eventId,
                @JsonProperty("eventType") String eventType,
                @JsonProperty("version") @JsonDeserialize(using = LegacyVersionDeserializer.class) int version,
                @JsonProperty("occurredAt") String occurredAt,
                @JsonProperty("producer") String producer,
                @JsonProperty("aggregateId") String aggregateId,
                @JsonProperty("traceId") @JsonDeserialize(using = SafeTraceIdDeserializer.class) String traceId,
                @JsonProperty("payload") Object payload) {
        }
    }

    /**
     * 仅供此 Reader 使用的本地载荷绑定规则；createdAt 不是当前资料初始化的拒收条件。
     */
    @JsonIgnoreProperties(value = {"createdAt"}, ignoreUnknown = true)
    abstract static class AccountCreatedPayloadMixin {

        /** 为本地载荷标注属性，不把 Jackson 注解泄漏到应用 Record。 */
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        AccountCreatedPayloadMixin(@JsonProperty("accountId") String accountId,
                @JsonProperty("accountType") String accountType,
                @JsonProperty("createdAt") String createdAt) {
        }
    }

    /**
     * 复现旧树解析 {@code asInt(-1)} 对 version 的接受范围，避免 v1 在重构时静默收紧。
     */
    static final class LegacyVersionDeserializer extends JsonDeserializer<Integer> {

        /** 将当前标量按旧规则转换为版本号；无效容器和 null 统一返回拒收值。 */
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                return parser.getNumberValue().intValue();
            }
            if (token == JsonToken.VALUE_STRING) {
                return NumberInput.parseAsInt(parser.getText(), -1);
            }
            if (token == JsonToken.VALUE_TRUE) {
                return 1;
            }
            if (token == JsonToken.VALUE_FALSE || token == JsonToken.VALUE_NULL) {
                return token == JsonToken.VALUE_FALSE ? 0 : -1;
            }
            if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
                parser.skipChildren();
            }
            return -1;
        }
    }

    /**
     * 仅保留文本形式的 traceId；非文本值不应影响事件接收，也不能污染日志上下文。
     */
    static final class SafeTraceIdDeserializer extends JsonDeserializer<String> {

        /** 提取文本 traceId；对象和数组被完整跳过以允许 Reader 继续处理未知兼容输入。 */
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_STRING) {
                return parser.getText();
            }
            if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
                parser.skipChildren();
            }
            return null;
        }
    }
}
