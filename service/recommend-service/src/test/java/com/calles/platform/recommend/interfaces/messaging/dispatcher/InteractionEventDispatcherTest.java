package com.calles.platform.recommend.interfaces.messaging.dispatcher;

import com.calles.platform.recommend.application.service.AuthorInteractionApplicationService;
import com.calles.platform.recommend.application.service.InteractionFeedbackApplicationService;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionAuthorActionMessage;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * InteractionEventDispatcher 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InteractionEventDispatcher 事件分发器测试")
class InteractionEventDispatcherTest {

    @Mock
    private InteractionFeedbackApplicationService interactionFeedbackService;

    @Mock
    private AuthorInteractionApplicationService authorInteractionService;

    private ObjectMapper objectMapper;
    private InteractionEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        dispatcher = new InteractionEventDispatcher(
                interactionFeedbackService,
                authorInteractionService,
                objectMapper
        );
    }

    @Test
    @DisplayName("分发视频互动事件 (interaction.video-action) 到 InteractionFeedbackApplicationService")
    void shouldDispatchVideoActionEvent() throws Exception {
        String json = """
                {
                    "eventId": "evt_video_001",
                    "eventType": "interaction.video-action",
                    "eventVersion": 1,
                    "traceId": "tr_001",
                    "payload": {
                        "userId": "u1",
                        "vid": "cv_100",
                        "action": "LIKE",
                        "state": "ACTIVE"
                    }
                }
                """;

        dispatcher.dispatch(InteractionEventEnvelope.EVENT_TYPE_VIDEO_ACTION, json);

        verify(interactionFeedbackService).handleInteractionEvent(argThat(message ->
                "evt_video_001".equals(message.eventId())
                        && "tr_001".equals(message.traceId())
                        && "u1".equals(message.payload().userId())
                        && "cv_100".equals(message.payload().vid())
                        && "LIKE".equals(message.payload().action())
                        && "ACTIVE".equals(message.payload().state())));
        verifyNoInteractions(authorInteractionService);
    }

    @Test
    @DisplayName("分发作者关注事件 (interaction.author-action) 到 AuthorInteractionApplicationService")
    void shouldDispatchAuthorActionEvent() throws Exception {
        String json = """
                {
                    "eventId": "evt_author_002",
                    "eventType": "interaction.author-action",
                    "eventVersion": 1,
                    "traceId": "tr_002",
                    "payload": {
                        "userId": "u1",
                        "authorId": "a2",
                        "action": "FOLLOW",
                        "state": "ACTIVE"
                    }
                }
                """;

        dispatcher.dispatch(InteractionEventEnvelope.EVENT_TYPE_AUTHOR_ACTION, json);

        verify(authorInteractionService).handleAuthorAction(any(InteractionAuthorActionMessage.class));
        verifyNoInteractions(interactionFeedbackService);
    }

    /** 缺失视频主键或行为时必须在统一入口丢弃，不能进入业务事务。 */
    @ParameterizedTest
    @ValueSource(strings = {"userId", "vid", "action"})
    void shouldDropVideoWithMissingRequiredField(String field) throws Exception {
        var root = objectMapper.createObjectNode();
        root.put("eventId", "evt_invalid_video");
        root.put("eventType", "interaction.video-action");
        root.put("eventVersion", 1);
        var payload = root.putObject("payload");
        payload.put("userId", "u1");
        payload.put("vid", "cv_100");
        payload.put("action", "LIKE");
        payload.put("state", "ACTIVE");
        payload.remove(field);

        dispatcher.dispatch("interaction.video-action", root.toString());

        verifyNoInteractions(interactionFeedbackService, authorInteractionService);
    }

    /** JSON 语法有效但载荷类型错误时不可触发无效重试。 */
    @Test
    void shouldDropPayloadWithInvalidFieldType() {
        String json = """
                {"eventId":"evt_bad_type","eventType":"interaction.video-action","eventVersion":1,
                 "payload":{"userId":"u1","vid":"v1","action":"WATCH_COMPLETED","creditedDuration":{}}}
                """;
        assertThatCode(() -> dispatcher.dispatch("interaction.video-action", json)).doesNotThrowAnyException();
        verifyNoInteractions(interactionFeedbackService, authorInteractionService);
    }

    /** 数据库临时故障仍须向监听器传播，由容器决定重试。 */
    @Test
    void shouldPropagateBusinessFailure() {
        String json = """
                {"eventId":"evt_retry","eventType":"interaction.video-action","eventVersion":1,
                 "payload":{"userId":"u1","vid":"v1","action":"LIKE","state":"ACTIVE"}}
                """;
        doThrow(new IllegalStateException("数据库暂不可用"))
                .when(interactionFeedbackService).handleInteractionEvent(any());
        assertThatThrownBy(() -> dispatcher.dispatch("interaction.video-action", json))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("未知事件类型时：安全忽略，不分发至任何处理器，亦不抛出异常")
    void shouldSafelyIgnoreUnknownEventType() throws Exception {
        String json = """
                {
                    "eventId": "evt_other_003",
                    "eventType": "interaction.unknown-topic",
                    "eventVersion": 1,
                    "payload": {
                        "key": "val"
                    }
                }
                """;

        dispatcher.dispatch("interaction.unknown-topic", json);

        verifyNoInteractions(interactionFeedbackService);
        verifyNoInteractions(authorInteractionService);
    }
}
