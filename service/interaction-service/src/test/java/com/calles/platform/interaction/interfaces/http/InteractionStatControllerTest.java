package com.calles.platform.interaction.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.interaction.application.query.InteractionQueryApplicationService;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 分享写入入口的 HTTP 登录校验回归测试，避免游客分享计数被累加。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class InteractionStatControllerTest {

    /** 被测 HTTP 入口，使用独立的服务替身校验写入是否发生。 */
    private MockMvc mockMvc;

    /** 用于确认分享调用次数的应用服务替身。 */
    @Mock
    private InteractionQueryApplicationService queryService;

    /**
     * 组装真实登录策略与异常映射，模拟服务内直接收到的游客请求。
     */
    @BeforeEach
    void setUp() {
        UserContext.clear();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new InteractionStatController(queryService, new InteractionAccessPolicy()))
                .setControllerAdvice(new InteractionExceptionHandler()).build();
    }

    /**
     * 游客调用分享接口必须返回 401，不能写入分享计数。
     */
    @Test
    @DisplayName("游客分享返回 401 且不增加计数")
    void anonymousShareMustNotWrite(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/interactions/videos/cv_100/share"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(queryService);
        assertThat(output).contains("交互业务异常: status=401, message=请先登录后再进行互动操作");
    }

    /**
     * 登录用户分享仍通过真实用户 ID 写入，与此前成功响应保持一致。
     */
    /**
     * 登录用户分享仍通过真实用户 ID 写入，与此前成功响应保持一致。
     */
    @Test
    @DisplayName("已登录用户分享成功且只以真实用户 ID 记账")
    void authenticatedShareRecordsUser() throws Exception {
        UserContext.set(new UserInfo("user_001", "USER", "user", "session_001"));
        try {
            mockMvc.perform(post("/api/interactions/videos/cv_100/share")
                            .header("Idempotency-Key", "idem_test_key_1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.action").value("SHARE"));
            verify(queryService).recordShare("cv_100", "user_001", "idem_test_key_1");
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 未携带 Idempotency-Key Header 的分享请求返回 400。
     */
    @Test
    @DisplayName("未携带 Idempotency-Key 抛出 400 参数异常")
    void shareWithoutIdempotencyKeyReturnsBadRequest() throws Exception {
        UserContext.set(new UserInfo("user_001", "USER", "user", "session_001"));
        try {
            mockMvc.perform(post("/api/interactions/videos/cv_100/share"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("分享请求必须在 Header 中携带有效的 Idempotency-Key"));
            verifyNoInteractions(queryService);
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 未知故障需要在服务端保留原因及堆栈，但客户端只能看到脱敏的 500 提示。
     *
     * @param output 测试期间收集的服务端日志
     */
    @Test
    @DisplayName("分享内部异常记录原因和堆栈且不泄露给客户端")
    void unexpectedShareFailureLogsCause(CapturedOutput output) throws Exception {
        UserContext.set(new UserInfo("user_001", "USER", "user", "session_001"));
        try {
            doThrow(new IllegalStateException("测试用写入失败原因"))
                    .when(queryService).recordShare("cv_100", "user_001", "idem_test_key_1");

            mockMvc.perform(post("/api/interactions/videos/cv_100/share")
                            .header("Idempotency-Key", "idem_test_key_1"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("服务器内部错误"));
            assertThat(output)
                    .contains("交互接口发生未预期异常: type=java.lang.IllegalStateException")
                    .contains("java.lang.IllegalStateException: 测试用写入失败原因")
                    .contains("at com.calles.platform.interaction.interfaces.http.InteractionStatController");
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 不支持的请求方法维持 405，不能因兜底日志处理而误报成 500。
     *
     * @param output 测试期间收集的服务端日志
     */
    @Test
    @DisplayName("不支持的分享方法维持 405 并记录错误类型")
    void unsupportedShareMethodKeeps405(CapturedOutput output) throws Exception {
        mockMvc.perform(put("/api/interactions/videos/cv_100/share"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
        verifyNoInteractions(queryService);
        assertThat(output).contains("交互接口请求错误: status=405, type=HttpRequestMethodNotSupportedException");
    }
}
