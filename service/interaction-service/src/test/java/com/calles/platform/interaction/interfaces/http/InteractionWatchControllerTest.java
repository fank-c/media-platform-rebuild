package com.calles.platform.interaction.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
import com.calles.platform.interaction.application.watch.WatchHeartbeatApplicationService;
import com.calles.platform.interaction.application.watch.WatchHeartbeatOutcome;
import com.calles.platform.interaction.application.watch.WatchProgressApplicationService;
import com.calles.platform.interaction.application.watch.WatchProgressView;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 观看心跳与历史接口协议测试。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class InteractionWatchControllerTest {

    private MockMvc mockMvc;

    @Mock
    private WatchHeartbeatApplicationService watchHeartbeatService;

    @Mock
    private WatchProgressApplicationService watchProgressService;

    @Mock
    private InteractionAccessPolicy accessPolicy;

    @BeforeEach
    void setUp() {
        InteractionWatchController controller =
                new InteractionWatchController(watchHeartbeatService, watchProgressService, accessPolicy);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InteractionExceptionHandler()).build();
    }

    @Test
    @DisplayName("POST /api/interactions/videos/{vid}/heartbeat 返回会话与服务端判定结果")
    void heartbeatReturnsSessionAndJudgements() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        when(watchHeartbeatService.processHeartbeat(eq("cv_100"), eq("user_001"), any()))
                .thenReturn(new WatchHeartbeatOutcome(
                        "cv_100", "sess_9", 12L, 125, 120, 35, 300, 90,
                        true, false, false, false));

        mockMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sessionId": "sess_9",
                                    "sequence": 12,
                                    "position": 125,
                                    "deltaDuration": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value("sess_9"))
                .andExpect(jsonPath("$.data.acceptedSequence").value(12))
                .andExpect(jsonPath("$.data.lastPosition").value(125))
                .andExpect(jsonPath("$.data.sessionWatchedDuration").value(35))
                .andExpect(jsonPath("$.data.videoDuration").value(300))
                .andExpect(jsonPath("$.data.qualificationThreshold").value(90))
                .andExpect(jsonPath("$.data.qualifiedThisSession").value(true))
                .andExpect(jsonPath("$.data.viewCountedThisSession").value(false))
                .andExpect(jsonPath("$.data.duplicateRequest").value(false));
    }

    @Test
    @DisplayName("起播请求携带 Header Idempotency-Key 且序号与增量为零时成功创建新会话")
    void startPlayWithIdempotencyKey() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        when(watchHeartbeatService.processHeartbeat(eq("cv_100"), eq("user_001"), any()))
                .thenReturn(new WatchHeartbeatOutcome(
                        "cv_100", "sess_new", 0L, 5, 0, 0, 100, 30,
                        false, true, false, false));

        mockMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .header("Idempotency-Key", "idemp_test_key_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\": 5, \"sequence\": 0, \"deltaDuration\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value("sess_new"))
                .andExpect(jsonPath("$.data.acceptedSequence").value(0))
                .andExpect(jsonPath("$.data.viewCountedThisSession").value(true))
                .andExpect(jsonPath("$.data.sessionWatchedDuration").value(0));
    }

    @Test
    @DisplayName("起播请求缺失 Idempotency-Key Header 返回 400")
    void startPlayWithoutIdempotencyKeyReturnsBadRequest() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        mockMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\": 0, \"sequence\": 0, \"deltaDuration\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("起播请求必须在 Header 中携带有效的 Idempotency-Key (不超过64字符)"));
        verifyNoInteractions(watchHeartbeatService);
    }

    @Test
    @DisplayName("起播请求携带非零序号或非零增量返回 400")
    void startPlayWithNonZeroSequenceOrDeltaReturnsBadRequest() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        mockMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .header("Idempotency-Key", "key_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\": 0, \"sequence\": 1, \"deltaDuration\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .header("Idempotency-Key", "key_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\": 0, \"sequence\": 0, \"deltaDuration\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(watchHeartbeatService);
    }

    @Test
    @DisplayName("后续心跳序号非正整数返回 400")
    void subsequentHeartbeatWithNonPositiveSequenceReturnsBadRequest() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        mockMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\": \"sess_1\", \"sequence\": 0, \"position\": 10, \"deltaDuration\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(watchHeartbeatService);
    }

    @Test
    @DisplayName("起播命中活跃会话返回 409 且包含现有会话标识与序号")
    void startPlayConflictReturnsActiveSessionData() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        when(watchHeartbeatService.processHeartbeat(eq("cv_100"), eq("user_001"), any()))
                .thenThrow(new com.calles.platform.interaction.exception.WatchSessionActiveException("sess_active_123", 5L));

        mockMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .header("Idempotency-Key", "new_start_key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\": 0, \"sequence\": 0, \"deltaDuration\": 0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("当前存在活跃观看会话，请恢复现有会话"))
                .andExpect(jsonPath("$.data.activeSessionId").value("sess_active_123"))
                .andExpect(jsonPath("$.data.acceptedSequence").value(5));
    }

    /**
     * 游客上报心跳必须被拒绝，且不得进入观看状态或播放计数的写入服务。
     */
    @Test
    @DisplayName("游客心跳返回 401 且不产生写入")
    void anonymousHeartbeatMustNotWrite() throws Exception {
        UserContext.clear();
        MockMvc anonymousMvc = MockMvcBuilders.standaloneSetup(
                new InteractionWatchController(watchHeartbeatService, watchProgressService,
                        new InteractionAccessPolicy()))
                .setControllerAdvice(new InteractionExceptionHandler()).build();

        anonymousMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":30,\"deltaDuration\":5}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(watchHeartbeatService);
    }

    @Test
    @DisplayName("GET /api/interactions/videos/{vid}/watch-progress 查询断点成功返回 200")
    void getWatchProgressSuccessfully() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.getCurrentUser()).thenReturn(Optional.of(sampleUser));
        when(watchProgressService.getProgress("cv_100", "user_001"))
                .thenReturn(new WatchProgressView("cv_100", 45, 45, 120, false));

        mockMvc.perform(get("/api/interactions/videos/cv_100/watch-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.vid").value("cv_100"))
                .andExpect(jsonPath("$.data.lastPosition").value(45));
    }

    /**
     * 游客进度应直接返回零进度，不查询任何共享断点。
     */
    @Test
    @DisplayName("游客观看进度返回零且不查询进度服务")
    void anonymousProgressMustNotReadSharedHistory() throws Exception {
        when(accessPolicy.getCurrentUser()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/interactions/videos/cv_100/watch-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastPosition").value(0))
                .andExpect(jsonPath("$.data.watchedDuration").value(0))
                .andExpect(jsonPath("$.data.videoDuration").value(0));
        verifyNoInteractions(watchProgressService);
    }

    /**
     * 非法 JSON 属于客户端输入错误，应保留 400 并记录异常类别而非按 500 处理。
     *
     * @param output 测试期间收集的服务端日志
     */
    @Test
    @DisplayName("无效心跳正文维持 400 并记录请求错误类型")
    void malformedHeartbeatKeepsBadRequest(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(watchHeartbeatService);
        assertThat(output)
                .contains("交互请求正文解析失败: type=HttpMessageNotReadableException, causeType=JsonParseException");
    }
}
