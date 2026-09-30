package com.calles.platform.recommend.interfaces.web;

import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.recommend.application.service.FeedbackApplicationService;
import com.calles.platform.recommend.application.service.RecommendFeedBufferService;
import com.calles.platform.recommend.application.service.UserBlockApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 推荐接口错误契约回归测试，通过 MVC 执行参数校验和异常映射。 */
class RecommendHttpErrorTest {
    /** 数据库与缓存应用服务不参与错误契约测试。 */
    private RecommendFeedBufferService buffer;
    /** 受测 HTTP 分发入口。 */
    private MockMvc mvc;

    /** 每次构建真实控制器与 MVC，清理线程身份避免测试串扰。 */
    @BeforeEach
    void setUp() {
        UserContext.clear();
        buffer = mock(RecommendFeedBufferService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RecommendFeedController(buffer,
                mock(FeedbackApplicationService.class), mock(UserBlockApplicationService.class)))
                .setControllerAdvice(new RecommendExceptionHandler())
                .build();
    }

    /** 未知反馈枚举及服务端专属行为均属于客户端参数错误。 */
    @Test
    void rejectsUnknownActions() throws Exception {
        for (String action : new String[]{"UNKNOWN", "LIKE", "WATCH_COMPLETED"}) {
            assertError(mvc.perform(post("/api/recommend/feedback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"vid\":\"cv_test\",\"actionType\":\"" + action + "\"}")), 400);
        }
    }

    /** 添加与撤销屏蔽都必须将非法维度映射为 400 而非 401。 */
    @Test
    void rejectsUnknownBlockTypes() throws Exception {
        assertError(mvc.perform(post("/api/recommend/blocks").header("X-User-Id", "user_test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blockType\":\"UNKNOWN\",\"targetId\":\"cv_test\"}")), 400);
        assertError(mvc.perform(delete("/api/recommend/blocks").header("X-User-Id", "user_test")
                .param("blockType", "UNKNOWN").param("targetId", "cv_test")), 400);
    }

    /** 请求体校验、JSON 格式及查询参数转换失败均输出统一外壳。 */
    @Test
    void rejectsInvalidRequests() throws Exception {
        assertError(mvc.perform(post("/api/recommend/feedback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"vid\":\"\",\"actionType\":\"PLAY\"}")), 400);
        assertError(mvc.perform(post("/api/recommend/blocks").header("X-User-Id", "user_test")
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 400);
        assertError(mvc.perform(post("/api/recommend/feedback").contentType(MediaType.APPLICATION_JSON)
                .content("{")), 400);
        assertError(mvc.perform(get("/api/recommend/feed").param("size", "invalid")), 400);
        assertError(mvc.perform(delete("/api/recommend/blocks").header("X-User-Id", "user_test")
                .param("blockType", "VIDEO")), 400);
    }

    /** 三个屏蔽端点缺少身份时均明确拒绝为 401。 */
    @Test
    void rejectsMissingIdentity() throws Exception {
        assertError(mvc.perform(post("/api/recommend/blocks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"blockType\":\"VIDEO\",\"targetId\":\"cv_test\"}")), 401);
        assertError(mvc.perform(delete("/api/recommend/blocks")
                .param("blockType", "VIDEO").param("targetId", "cv_test")), 401);
        assertError(mvc.perform(get("/api/recommend/blocks")), 401);
    }

    /** 不支持的请求格式保留框架 Accept 响应头，同时使用统一错误外壳。 */
    @Test
    void preservesSupportedMediaTypesOn415() throws Exception {
        assertError(mvc.perform(post("/api/recommend/blocks")
                .header("X-User-Id", "user_test")
                .contentType(MediaType.TEXT_PLAIN).content("unsupported")), 415)
                .andExpect(header().string("Accept", "application/json, application/*+json"));
    }

    /** 未知故障只返回通用提示，不能泄漏底层细节。 */
    @Test
    void hidesUnexpectedFailure() throws Exception {
        when(buffer.consumeFeed(isNull(), anyInt())).thenThrow(new IllegalStateException("internal-detail"));
        assertError(mvc.perform(get("/api/recommend/feed")), 500)
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }

    /** 断言 HTTP 状态与统一响应码一致，错误数据为空且有安全提示。 */
    private ResultActions assertError(ResultActions result, int code) throws Exception {
        return result.andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.data").isEmpty());
    }
}
