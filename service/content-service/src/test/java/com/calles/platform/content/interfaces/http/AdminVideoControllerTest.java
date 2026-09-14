package com.calles.platform.content.interfaces.http;

import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.content.application.security.ContentAccessPolicy;
import com.calles.platform.content.application.video.VideoModerationApplicationService;
import com.calles.platform.content.application.video.VideoQueryApplicationService;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;

import java.util.List;

import com.calles.platform.content.interfaces.http.video.AdminVideoController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminVideoController 管理后台 HTTP API 控制器契约与端点测试。
 * <p>
 * 覆盖管理端多条件分页筛选、违规视频封禁以及误判解封等关键运营接口。
 */
@DisplayName("AdminVideoController 管理端控制器测试")
class AdminVideoControllerTest {

    /** Spring MVC 模拟执行器。 */
    private MockMvc mockMvc;

    /** 模拟治理应用服务。 */
    private VideoModerationApplicationService moderationService;

    /** 模拟读模型查询服务。 */
    private VideoQueryApplicationService queryService;

    /** 模拟权限门禁策略。 */
    private ContentAccessPolicy accessPolicy;

    /** 管理员上下文测试样例。 */
    private UserInfo sampleAdmin;

    /**
     * 测试前置初始化。
     */
    @BeforeEach
    void setUp() {
        moderationService = Mockito.mock(VideoModerationApplicationService.class);
        queryService = Mockito.mock(VideoQueryApplicationService.class);
        accessPolicy = Mockito.mock(ContentAccessPolicy.class);

        sampleAdmin = new UserInfo("admin_001", "ADMIN", "ADMIN", "session_admin", "device_admin");

        AdminVideoController controller = new AdminVideoController(
                moderationService, queryService, accessPolicy
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ContentExceptionHandler())
                .build();
    }

    /**
     * 测试 POST /api/content/videos/admin/list 管理员多条件分页检索视频。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("POST /api/content/videos/admin/list 管理员分页检索视频列表成功")
    void listAdminVideosSuccessfully() throws Exception {
        // 步骤 1: 插桩管理员身份校验与查询服务返回值
        when(accessPolicy.requireAdmin()).thenReturn(sampleAdmin);
        VideoResponses.AdminItem item = new VideoResponses.AdminItem(
                "v_100", "cv10086", "author_1", "标题", "cf",
                "ACTIVE", "PUBLISHED", null,
                null, null
        );
        VideoResponses.AdminPage page = new VideoResponses.AdminPage(List.of(item), 1L, 1L, 20L);
        when(queryService.listAdminVideos(any(), eq(1), eq(20))).thenReturn(page);

        String requestJson = "{\"authorId\":\"author_1\",\"publishStatus\":\"PUBLISHED\"}";

        // 步骤 2: 发起 POST 请求并验证响应
        mockMvc.perform(post("/api/content/videos/admin/list?page=1&size=20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].vid").value("cv10086"));
    }

    /**
     * 测试 POST /api/content/videos/admin/{id}/ban 管理员封禁视频违规内容。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("POST /api/content/videos/admin/{id}/ban 管理员封禁视频成功")
    void banVideoSuccessfully() throws Exception {
        // 步骤 1: 插桩管理员身份校验
        when(accessPolicy.requireAdmin()).thenReturn(sampleAdmin);

        String requestJson = "{\"reason\":\"内容涉嫌严重侵权违规\"}";

        // 步骤 2: 发起管理员封禁请求
        mockMvc.perform(post("/api/content/videos/admin/v_100/ban")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 步骤 3: 验证风控治理服务被正确调用
        verify(moderationService).banVideo("admin_001", "v_100", "内容涉嫌严重侵权违规");
    }

    /**
     * 测试 POST /api/content/videos/admin/{id}/unban 管理员解封视频。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("POST /api/content/videos/admin/{id}/unban 管理员解封视频成功")
    void unbanVideoSuccessfully() throws Exception {
        // 步骤 1: 插桩管理员身份校验
        when(accessPolicy.requireAdmin()).thenReturn(sampleAdmin);

        // 步骤 2: 发起管理员解封请求
        mockMvc.perform(post("/api/content/videos/admin/v_100/unban"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 步骤 3: 验证风控治理服务解封调用
        verify(moderationService).unbanVideo("admin_001", "v_100");
    }
}
