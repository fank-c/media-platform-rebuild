package com.calles.platform.interaction.interfaces.http;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.interaction.application.like.LikeApplicationService;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
import com.calles.platform.interaction.domain.model.like.VideoLike;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InteractionLikeControllerTest {

    private MockMvc mockMvc;

    @Mock
    private LikeApplicationService likeService;

    @Mock
    private InteractionAccessPolicy accessPolicy;

    @BeforeEach
    void setUp() {
        InteractionLikeController controller = new InteractionLikeController(likeService, accessPolicy);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InteractionExceptionHandler()).build();
    }

    @Test
    @DisplayName("POST /api/interactions/videos/{vid}/like 点赞成功返回 200")
    void likeVideoSuccessfully() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        when(likeService.likeVideo("cv_100", "user_001")).thenReturn(true);

        mockMvc.perform(post("/api/interactions/videos/cv_100/like"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.vid").value("cv_100"))
                .andExpect(jsonPath("$.data.action").value("LIKE"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    @DisplayName("DELETE /api/interactions/videos/{vid}/like 取消点赞成功返回 200")
    void unlikeVideoSuccessfully() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        when(likeService.unlikeVideo("cv_100", "user_001")).thenReturn(false);

        mockMvc.perform(delete("/api/interactions/videos/cv_100/like"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.vid").value("cv_100"))
                .andExpect(jsonPath("$.data.action").value("UNLIKE"))
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    @DisplayName("GET /api/interactions/likes 分页查询本人点赞列表成功返回 200")
    void getLikedVideosSuccessfully() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        VideoLike like = VideoLike.create("cv_200", "user_001");
        when(likeService.getLikedVideos(eq("user_001"), eq(1), eq(20))).thenReturn(List.of(like));

        mockMvc.perform(get("/api/interactions/likes")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(like.getId()))
                .andExpect(jsonPath("$.data[0].vid").value("cv_200"));
    }

    @Test
    @DisplayName("GET /api/interactions/likes 游客访问返回 401")
    void anonymousGetLikesMustReturn401() throws Exception {
        UserContext.clear();
        MockMvc anonymousMvc = MockMvcBuilders.standaloneSetup(
                new InteractionLikeController(likeService, new InteractionAccessPolicy()))
                .setControllerAdvice(new InteractionExceptionHandler()).build();

        anonymousMvc.perform(get("/api/interactions/likes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(likeService);
    }

    /**
     * 验证已有的点赞登录门禁也由受控异常映射为 401，而不是 500。
     */
    @Test
    @DisplayName("游客点赞返回 401 且不写入点赞状态")
    void anonymousLikeMustNotWrite() throws Exception {
        UserContext.clear();
        MockMvc anonymousMvc = MockMvcBuilders.standaloneSetup(
                new InteractionLikeController(likeService, new InteractionAccessPolicy()))
                .setControllerAdvice(new InteractionExceptionHandler()).build();

        anonymousMvc.perform(post("/api/interactions/videos/cv_100/like"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(likeService);
    }
}
