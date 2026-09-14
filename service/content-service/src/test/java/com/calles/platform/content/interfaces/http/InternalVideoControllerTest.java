package com.calles.platform.content.interfaces.http;

import com.calles.platform.content.application.stream.VideoStreamApplicationService;
import com.calles.platform.content.application.video.VideoAuditCallbackApplicationService;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import com.calles.platform.content.interfaces.http.video.InternalVideoController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InternalVideoController 内部微服务 RPC 与异步回调 HTTP API 控制器契约测试。
 * <p>
 * 覆盖机审/人审判定结果回调以及转码完成规格注册回调的 HTTP 响应与委托调用验证。
 */
@DisplayName("InternalVideoController 内部回调控制器测试")
class InternalVideoControllerTest {

    /** Spring MVC 模拟执行器。 */
    private MockMvc mockMvc;

    /** 模拟审核异步回调服务。 */
    private VideoAuditCallbackApplicationService auditCallbackService;

    /** 模拟多清晰度转码流服务。 */
    private VideoStreamApplicationService streamService;

    /**
     * 测试前置初始化。
     */
    @BeforeEach
    void setUp() {
        auditCallbackService = Mockito.mock(VideoAuditCallbackApplicationService.class);
        streamService = Mockito.mock(VideoStreamApplicationService.class);

        InternalVideoController controller = new InternalVideoController(auditCallbackService, streamService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ContentExceptionHandler())
                .build();
    }

    /**
     * 测试 POST /api/content/videos/internal/audit-callback 接收审核判定结果回调。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("POST /api/content/videos/internal/audit-callback 接收审核回调成功")
    void auditCallbackSuccessfully() throws Exception {
        // 步骤 1: 准备审核回调 Payload
        String requestJson = """
                {
                    "videoId": "v_100",
                    "passed": true
                }
                """;

        // 步骤 2: 发起内部回调请求并校验 200 OK
        mockMvc.perform(post("/api/content/videos/internal/audit-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 步骤 3: 验证回调服务处理调用
        verify(auditCallbackService).handleAuditCallback(any(VideoRequests.AuditCallback.class));
    }

    /**
     * 测试 POST /api/content/videos/internal/transcode-callback 接收切片转码完成回调。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("POST /api/content/videos/internal/transcode-callback 接收转码回调成功")
    void transcodeCallbackSuccessfully() throws Exception {
        // 步骤 1: 准备转码回调 Payload
        String requestJson = """
                {
                    "videoId": "v_100",
                    "quality": "1080P",
                    "format": "MP4",
                    "codec": "H264",
                    "fileId": "f_1080",
                    "fileSize": 2048000,
                    "bitrate": 3000,
                    "fps": 30,
                    "status": "COMPLETED"
                }
                """;

        // 步骤 2: 发起内部转码回调请求并校验 200 OK
        mockMvc.perform(post("/api/content/videos/internal/transcode-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 步骤 3: 验证转码流注册服务调用
        verify(streamService).registerStream(any(VideoRequests.TranscodeCallback.class));
    }
}
