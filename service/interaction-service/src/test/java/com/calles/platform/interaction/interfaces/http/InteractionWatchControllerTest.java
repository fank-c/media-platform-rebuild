package com.calles.platform.interaction.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class InteractionWatchControllerTest {

    private MockMvc mockMvc;

    @Mock
    private WatchHeartbeatApplicationService watchService;

    @Mock
    private InteractionAccessPolicy accessPolicy;

    @BeforeEach
    void setUp() {
        InteractionWatchController controller = new InteractionWatchController(watchService, accessPolicy);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InteractionExceptionHandler()).build();
    }

    @Test
    @DisplayName("POST /api/interactions/videos/{vid}/heartbeat 上报心跳成功返回 200")
    void heartbeatSuccessfully() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        WatchHistory history = WatchHistory.create("user_001", "cv_100", 30, 5, 120);
        when(watchService.processHeartbeat(eq("cv_100"), eq("user_001"), eq(30), eq(5), eq(120)))
                .thenReturn(history);

        String json = """
                {
                    "position": 30,
                    "deltaDuration": 5,
                    "videoDuration": 120
                }
                """;

        mockMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.vid").value("cv_100"))
                .andExpect(jsonPath("$.data.lastPosition").value(30));
    }

    /**
     * 游客上报心跳必须被拒绝，且不得进入观看历史或播放计数的写入服务。
     */
    @Test
    @DisplayName("游客心跳返回 401 且不产生写入")
    void anonymousHeartbeatMustNotWrite() throws Exception {
        UserContext.clear();
        MockMvc anonymousMvc = MockMvcBuilders.standaloneSetup(
                new InteractionWatchController(watchService, new InteractionAccessPolicy()))
                .setControllerAdvice(new InteractionExceptionHandler()).build();

        anonymousMvc.perform(post("/api/interactions/videos/cv_100/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":30,\"deltaDuration\":5,\"videoDuration\":120}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(watchService);
    }

    @Test
    @DisplayName("GET /api/interactions/videos/{vid}/watch-progress 查询断点成功返回 200")
    void getWatchProgressSuccessfully() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.getCurrentUser()).thenReturn(Optional.of(sampleUser));

        WatchHistory history = WatchHistory.create("user_001", "cv_100", 45, 45, 120);
        when(watchService.getWatchProgress("cv_100", "user_001")).thenReturn(Optional.of(history));

        mockMvc.perform(get("/api/interactions/videos/cv_100/watch-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.vid").value("cv_100"))
                .andExpect(jsonPath("$.data.lastPosition").value(45));
    }

    /**
     * 游客进度应直接返回空快照，不查询曾用共用标识保存的匿名历史。
     */
    @Test
    @DisplayName("游客观看进度返回零且不查询匿名历史")
    void anonymousProgressMustNotReadSharedHistory() throws Exception {
        when(accessPolicy.getCurrentUser()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/interactions/videos/cv_100/watch-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastPosition").value(0))
                .andExpect(jsonPath("$.data.watchedDuration").value(0));
        verifyNoInteractions(watchService);
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
        verifyNoInteractions(watchService);
        assertThat(output)
                .contains("交互请求正文解析失败: type=HttpMessageNotReadableException, causeType=JsonParseException");
    }
}
