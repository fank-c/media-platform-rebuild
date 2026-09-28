package com.calles.platform.recommend.interfaces.messaging.dispatcher;

import com.calles.platform.recommend.application.service.AuthorInteractionApplicationService;
import com.calles.platform.recommend.application.service.InteractionFeedbackApplicationService;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionAuthorActionMessage;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionEventEnvelope;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionVideoActionMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 推荐微服务统一交互行为事件分发器 (InteractionEventDispatcher)。
 *
 * <p>核心职责：
 * <ul>
 *   <li>依据 {@code eventType} 将来自统一消费队列的交互事件准确路由至专用业务处理器；</li>
 *   <li>将通用报文精确反序列化为具体事件的强类型契约模型；</li>
 *   <li>对未识别的扩展事件类型进行安全忽略防御，防止阻塞整条物理消费队列。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionEventDispatcher {

    private final InteractionFeedbackApplicationService interactionFeedbackService;
    private final AuthorInteractionApplicationService authorInteractionService;
    private final ObjectMapper objectMapper;

    /**
     * 根据事件类型分发并驱动具体领域逻辑。
     *
     * @param eventType 领域事件类型标识
     * @param rawPayload 原始 JSON 字符串
     * @throws Exception 当下游业务服务抛出未捕获异常时向上传播，驱动 MQ 重试
     */
    public void dispatch(String eventType, String rawPayload) throws Exception {
        if (eventType == null || eventType.isBlank()) {
            log.warn("交互事件分发器接收到空的 eventType，安全跳过");
            return;
        }

        switch (eventType) {
            case InteractionEventEnvelope.EVENT_TYPE_VIDEO_ACTION -> {
                InteractionVideoActionMessage videoMessage =
                        parseMessage(rawPayload, InteractionVideoActionMessage.class);
                // 视频服务依赖这些字段写入幂等记录和画像，非法载荷不能进入业务事务。
                if (videoMessage == null || videoMessage.payload() == null
                        || isBlank(videoMessage.payload().userId())
                        || isBlank(videoMessage.payload().vid())
                        || isBlank(videoMessage.payload().action())) {
                    log.warn("视频互动事件关键字段缺失，丢弃处理");
                    return;
                }
                interactionFeedbackService.handleInteractionEvent(videoMessage);
            }
            case InteractionEventEnvelope.EVENT_TYPE_AUTHOR_ACTION -> {
                InteractionAuthorActionMessage authorMessage =
                        parseMessage(rawPayload, InteractionAuthorActionMessage.class);
                if (authorMessage == null || authorMessage.payload() == null) {
                    log.warn("作者互动事件载荷缺失，丢弃处理: rawPayload={}", rawPayload);
                    return;
                }
                authorInteractionService.handleAuthorAction(authorMessage);
            }
            default -> log.warn("未知的交互领域事件类型，安全忽略避免阻塞共享队列: eventType={}", eventType);
        }
    }

    /** 判断必填文本是否缺失，避免不可恢复的缺字段消息进入重试。 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 解析当前契约载荷。字段类型错误不可通过重试恢复，返回空值供分发分支丢弃。
     * 只捕获 JSON 解析异常，业务处理和数据库异常仍由调用方传播。
     *
     * @param rawPayload 原始消息
     * @param messageType 当前事件类型的载荷模型
     * @return 解析后的消息，格式错误时为空
     */
    private <T> T parseMessage(String rawPayload, Class<T> messageType) {
        try {
            return objectMapper.readValue(rawPayload, messageType);
        } catch (JsonProcessingException exception) {
            log.warn("交互事件载荷不符合当前契约，丢弃处理: messageType={}", messageType.getSimpleName());
            return null;
        }
    }
}
