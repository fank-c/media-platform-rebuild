package com.calles.platform.interaction.interfaces.http;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.calles.platform.common.web.context.UserInfo;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InteractionWatchControllerTest {

    private MockMvc mockMvc;

    @Mock
    private WatchHeartbeatApplicationService watchService;

    @Mock
    private InteractionAccessPolicy accessPolicy;

    @BeforeEach
    void setUp() {
        InteractionWatchController controller = new InteractionWatchController(watchService, accessPolicy);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /api/interactions/videos/{vid}/heartbeat 上报心跳成功返回 200")
    void heartbeatSuccessfully() throws Exception {
        UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");
        when(accessPolicy.getCurrentUser()).thenReturn(Optional.of(sampleUser));

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
}
