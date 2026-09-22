package com.calles.platform.user.interfaces.http.follow;

import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.user.application.follow.UserFollowApplicationService;
import com.calles.platform.user.application.security.UserAccessPolicy;
import com.calles.platform.user.domain.follow.FollowStatus;
import com.calles.platform.user.domain.follow.RelationType;
import com.calles.platform.user.interfaces.http.advice.UserExceptionHandler;
import com.calles.platform.user.interfaces.http.follow.dto.FollowResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户关注 HTTP 控制器端点测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserFollowController HTTP 接口契约测试")
class UserFollowControllerTest {

    private static final String CURRENT_USER = "user_me_123456";
    private static final String TARGET_USER = "user_creator_8888";

    @Mock
    private UserFollowApplicationService followService;
    @Mock
    private UserAccessPolicy accessPolicy;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserFollowController controller = new UserFollowController(followService, accessPolicy);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new UserExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/users/{targetUserId}/follow 关注用户成功返回 200")
    void followEndpointSuccess() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(new UserInfo(CURRENT_USER, "USER", "user", "sess-1"));
        when(followService.follow(CURRENT_USER, TARGET_USER))
                .thenReturn(new FollowResponses.Action(TARGET_USER, FollowStatus.FOLLOWING, false));

        mockMvc.perform(post("/api/users/{targetUserId}/follow", TARGET_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.targetUserId").value(TARGET_USER))
                .andExpect(jsonPath("$.data.followStatus").value(1))
                .andExpect(jsonPath("$.data.mutual").value(false));

        verify(followService).follow(CURRENT_USER, TARGET_USER);
    }

    @Test
    @DisplayName("DELETE /api/users/{targetUserId}/follow 取关用户成功返回 200")
    void unfollowEndpointSuccess() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(new UserInfo(CURRENT_USER, "USER", "user", "sess-1"));
        when(followService.unfollow(CURRENT_USER, TARGET_USER))
                .thenReturn(new FollowResponses.Action(TARGET_USER, FollowStatus.UNFOLLOWED, false));

        mockMvc.perform(delete("/api/users/{targetUserId}/follow", TARGET_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.targetUserId").value(TARGET_USER))
                .andExpect(jsonPath("$.data.followStatus").value(0));

        verify(followService).unfollow(CURRENT_USER, TARGET_USER);
    }

    @Test
    @DisplayName("GET /api/users/{targetUserId}/relation 查询关系状态成功")
    void getRelationEndpointSuccess() throws Exception {
        when(followService.getRelation(any(), eq(TARGET_USER)))
                .thenReturn(new FollowResponses.Relation(TARGET_USER, RelationType.MUTUAL));

        mockMvc.perform(get("/api/users/{targetUserId}/relation", TARGET_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.targetUserId").value(TARGET_USER))
                .andExpect(jsonPath("$.data.relation").value("MUTUAL"));
    }

    @Test
    @DisplayName("GET /api/users/{accountId}/following 查询关注列表成功")
    void getFollowingEndpointSuccess() throws Exception {
        when(accessPolicy.requireAuthenticated()).thenReturn(new UserInfo(CURRENT_USER, "USER", "user", "sess-1"));
        FollowResponses.FollowItem item = new FollowResponses.FollowItem(
                TARGET_USER, "科技小汪", "https://img.test/avatar.jpg", "专注科技",
                LocalDateTime.now(), true
        );
        when(followService.getFollowingList(eq(CURRENT_USER), any(), eq(1L), eq(20L)))
                .thenReturn(new FollowResponses.Page(1L, 1L, 20L, List.of(item)));

        mockMvc.perform(get("/api/users/{accountId}/following", CURRENT_USER)
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].accountId").value(TARGET_USER))
                .andExpect(jsonPath("$.data.items[0].nickname").value("科技小汪"));
    }

    @Test
    @DisplayName("GET /api/users/{accountId}/stats 查询关系统计快照成功")
    void getStatsEndpointSuccess() throws Exception {
        when(accessPolicy.requireAuthenticated()).thenReturn(new UserInfo(CURRENT_USER, "USER", "user", "sess-1"));
        when(followService.getStats(CURRENT_USER))
                .thenReturn(new FollowResponses.Stats(CURRENT_USER, 15L, 300L));

        mockMvc.perform(get("/api/users/{accountId}/stats", CURRENT_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accountId").value(CURRENT_USER))
                .andExpect(jsonPath("$.data.followingCount").value(15))
                .andExpect(jsonPath("$.data.followerCount").value(300));
    }
}
