package com.calles.platform.content.interfaces.http;

import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.content.application.security.ContentAccessPolicy;
import com.calles.platform.content.application.video.VideoQueryApplicationService;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.calles.platform.content.interfaces.http.video.PortalVideoController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PortalVideoController 前台消费端 HTTP API 控制器契约与端点测试。
 * <p>
 * 覆盖视频图文详情公开查询、多清晰度播放流获取的 HTTP 响应与匿名/登录状态处理。
 */
@DisplayName("PortalVideoController 消费端控制器测试")
class PortalVideoControllerTest {

    /** Spring MVC 模拟执行器。 */
    private MockMvc mockMvc;

    /** 模拟视频读模型查询应用服务。 */
    private VideoQueryApplicationService queryService;

    /** 模拟权限门禁策略。 */
    private ContentAccessPolicy accessPolicy;

    /** 普通用户上下文测试样例。 */
    private UserInfo sampleUser;

    /**
     * 测试前置初始化。
     */
    @BeforeEach
    void setUp() {
        queryService = Mockito.mock(VideoQueryApplicationService.class);
        accessPolicy = Mockito.mock(ContentAccessPolicy.class);

        sampleUser = new UserInfo("user_001", "USER", "NORMAL", "session_1", "device_1");

        PortalVideoController controller = new PortalVideoController(queryService, accessPolicy);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ContentExceptionHandler())
                .build();
    }

    /**
     * 测试 GET /api/content/videos/{vid} 获取视频详情，返回 200 OK 及完整聚合字段。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("GET /api/content/videos/{vid} 获取视频详情成功")
    void getVideoDetailSuccessfully() throws Exception {
        // 步骤 1: 插桩当前登录用户
        when(accessPolicy.getCurrentUser()).thenReturn(Optional.of(sampleUser));

        VideoResponses.Detail detail = new VideoResponses.Detail(
                "v_100",
                "cv_test_001",
                "author_1",
                "微服务视频",
                "简介文本",
                "cf_1",
                "vf_1",
                120,
                List.of("Java"),
                "ACTIVE",
                "PUBLISHED",
                "PUBLIC",
                1000L, 50L, 10L, 20L, 5L,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(queryService.getVideoDetail("cv_test_001", "user_001", false)).thenReturn(detail);

        // 步骤 2: 发起 GET 请求并校验 JSON 响应
        mockMvc.perform(get("/api/content/videos/cv_test_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.vid").value("cv_test_001"))
                .andExpect(jsonPath("$.data.title").value("微服务视频"));
    }

    /**
     * 测试 GET /api/content/videos/{vid}/streams 获取可用播放流切片列表，返回 200 OK。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("GET /api/content/videos/{vid}/streams 获取可用播放流成功")
    void getPlayStreamsSuccessfully() throws Exception {
        // 步骤 1: 模拟匿名游客访问
        when(accessPolicy.getCurrentUser()).thenReturn(Optional.empty());

        VideoResponses.PlayStream stream = new VideoResponses.PlayStream(
                "1080P", "MP4", "H264", "f_1080", 5000000L, 4000, 30
        );
        VideoResponses.PlayStreams playStreams = new VideoResponses.PlayStreams(
                "cv_test_001", "微服务视频", List.of(stream)
        );

        when(queryService.getPlayStreams("cv_test_001", null, false)).thenReturn(playStreams);

        // 步骤 2: 发起 GET 请求并断言清晰度与文件 ID
        mockMvc.perform(get("/api/content/videos/cv_test_001/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.streams[0].quality").value("1080P"))
                .andExpect(jsonPath("$.data.streams[0].fileId").value("f_1080"));
    }
}
