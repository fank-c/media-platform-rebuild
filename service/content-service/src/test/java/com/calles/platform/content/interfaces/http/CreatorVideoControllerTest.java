package com.calles.platform.content.interfaces.http;

import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.content.application.security.ContentAccessPolicy;
import com.calles.platform.content.application.video.VideoPublishApplicationService;
import com.calles.platform.content.application.video.VideoQueryApplicationService;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;
import java.util.List;

import com.calles.platform.content.interfaces.http.video.CreatorVideoController;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CreatorVideoController 创作者端 HTTP API 控制器契约与端点测试。
 * <p>
 * 覆盖草稿创建、元数据更新、提审、下架、删除与作品列表查询的 HTTP 契约与权限门禁校验。
 */
@DisplayName("CreatorVideoController 创作者端控制器测试")
class CreatorVideoControllerTest {

    /** Spring MVC 模拟执行器。 */
    private MockMvc mockMvc;

    /** 模拟视频发布应用服务。 */
    private VideoPublishApplicationService publishService;

    /** 模拟视频查询应用服务。 */
    private VideoQueryApplicationService queryService;

    /** 模拟权限门禁策略。 */
    private ContentAccessPolicy accessPolicy;

    /** 模拟流水线任务协调器。 */
    private com.calles.platform.content.application.task.VideoTaskCoordinator videoTaskCoordinator;

    /** 创作者上下文测试样例。 */
    private UserInfo sampleUser;

    /**
     * 测试前置初始化。
     */
    @BeforeEach
    void setUp() {
        publishService = Mockito.mock(VideoPublishApplicationService.class);
        queryService = Mockito.mock(VideoQueryApplicationService.class);
        accessPolicy = Mockito.mock(ContentAccessPolicy.class);
        videoTaskCoordinator = Mockito.mock(com.calles.platform.content.application.task.VideoTaskCoordinator.class);

        sampleUser = new UserInfo("user_001", "USER", "NORMAL", "session_1", "device_1");

        CreatorVideoController controller = new CreatorVideoController(
                publishService, queryService, accessPolicy, videoTaskCoordinator
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ContentExceptionHandler())
                .build();
    }


    /**
     * 测试 POST /api/content/videos/draft 创作者新建草稿，返回 201 与业务短码 vid。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("POST /api/content/videos/draft 创建草稿成功返回 201 与 vid")
    void createDraftSuccessfully() throws Exception {
        // 步骤 1: 插桩权限策略与服务返回值
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        when(publishService.createDraft(eq("user_001"), any(VideoRequests.CreateDraft.class)))
                .thenReturn("cv0123456789012345678901");

        String requestJson = """
                {
                    "title": "Spring Cloud 微服务",
                    "description": "简介",
                    "videoFileId": "vf_001",
                    "coverFileId": "cf_001",
                    "duration": 300,
                    "tags": "Java,微服务"
                }
                """;

        // 步骤 2: 发起 POST 请求并验证 201 状态与短码
        mockMvc.perform(post("/api/content/videos/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.vid").value("cv0123456789012345678901"));
    }

    /**
     * 测试 PUT /api/content/videos/{id} 创作者更新元数据，返回 200 OK。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("PUT /api/content/videos/{id} 更新元数据成功返回 200")
    void updateMetadataSuccessfully() throws Exception {
        // 步骤 1: 插桩登录用户身份
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        String requestJson = """
                {
                    "title": "新标题",
                    "description": "新简介",
                    "tags": "Java"
                }
                """;

        // 步骤 2: 发起 PUT 请求并验证响应
        mockMvc.perform(put("/api/content/videos/v_100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 步骤 3: 验证服务调用
        verify(publishService).updateMetadata(eq("user_001"), eq("v_100"), any(VideoRequests.UpdateMetadata.class));
    }

    /**
     * 测试 POST /api/content/videos/{id}/submit 提交审核，返回 200 OK。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("POST /api/content/videos/{id}/submit 提交审核返回 200")
    void submitForAuditSuccessfully() throws Exception {
        // 步骤 1: 插桩登录用户身份
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        // 步骤 2: 发起 POST 提交审核请求并验证响应
        mockMvc.perform(post("/api/content/videos/v_100/submit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 步骤 3: 验证提审服务调用
        verify(publishService).submitForAudit("user_001", "v_100");
    }

    /**
     * 测试 POST /api/content/videos/{id}/offline 创作者主动下架，返回 200 OK。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("POST /api/content/videos/{id}/offline 主动下架视频返回 200")
    void takeOfflineSuccessfully() throws Exception {
        // 步骤 1: 插桩登录用户身份
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        String requestJson = "{\"reason\":\"作者自行决定暂停公开\"}";

        // 步骤 2: 发起下架请求并验证响应
        mockMvc.perform(post("/api/content/videos/v_100/offline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 步骤 3: 验证下架服务调用
        verify(publishService).takeOffline("user_001", "v_100", "作者自行决定暂停公开");
    }

    /**
     * 测试 DELETE /api/content/videos/{id} 删除视频，返回 204 No Content。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("DELETE /api/content/videos/{id} 删除视频返回 204")
    void deleteVideoSuccessfully() throws Exception {
        // 步骤 1: 插桩登录用户身份
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        // 步骤 2: 发起 DELETE 请求并验证 204 状态码
        mockMvc.perform(delete("/api/content/videos/v_100"))
                .andExpect(status().isNoContent());

        // 步骤 3: 验证服务层删除调用
        verify(publishService).deleteVideo("user_001", "v_100");
    }

    /**
     * 测试 GET /api/content/videos/me 创作者分页查询本人作品列表，返回 200 OK。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("GET /api/content/videos/me 查询创作者本人作品列表成功")
    void listMyVideosSuccessfully() throws Exception {
        // 步骤 1: 插桩登录用户身份与查询服务返回值
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        VideoResponses.CreatorItem item = new VideoResponses.CreatorItem(
                "v_100", "cv10086", "测试视频", "cf_1", 300,
                "ACTIVE", "PUBLISHED", null,
                null, null, null
        );
        VideoResponses.CreatorPage page = new VideoResponses.CreatorPage(List.of(item), 1L, 1L, 10L);
        when(queryService.listMyVideos("user_001", "PUBLISHED", 1, 10)).thenReturn(page);

        // 步骤 2: 发起 GET 请求并验证分页响应
        mockMvc.perform(get("/api/content/videos/me?publishStatus=PUBLISHED&page=1&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].vid").value("cv10086"));
    }

    /**
     * 测试 GET /api/content/videos/{id}/tasks 创作者查询发布流水线任务进度。
     */
    @Test
    @DisplayName("GET /api/content/videos/{id}/tasks 查询流水线任务进度成功")
    void getPipelineTasksSuccessfully() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        com.calles.platform.content.domain.model.video.VideoContent video =
                com.calles.platform.content.domain.model.video.VideoContent.createDraft(
                        "v_100", "cv10086", "user_001", "标题", "简介", "fv", "fc", 120, "tag"
                );
        when(queryService.findVideoOrThrow("v_100")).thenReturn(video);

        VideoResponses.TaskProgressItem item = new VideoResponses.TaskProgressItem(
                "task_1", "AUDIT", "内容合规审核", "SUCCESS", 100, 0, null, null, null
        );
        VideoResponses.PipelineProgress progress = new VideoResponses.PipelineProgress(
                "v_100", "AUDITING", false, List.of(item)
        );
        when(videoTaskCoordinator.getPipelineProgress("v_100")).thenReturn(progress);

        mockMvc.perform(get("/api/content/videos/v_100/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.videoId").value("v_100"))
                .andExpect(jsonPath("$.data.tasks[0].taskType").value("AUDIT"))
                .andExpect(jsonPath("$.data.tasks[0].status").value("SUCCESS"));
    }
}

