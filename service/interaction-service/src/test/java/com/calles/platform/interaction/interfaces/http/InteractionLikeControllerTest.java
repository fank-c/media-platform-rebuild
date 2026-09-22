package com.calles.platform.interaction.interfaces.http;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.interaction.application.like.LikeApplicationService;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
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
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
}
