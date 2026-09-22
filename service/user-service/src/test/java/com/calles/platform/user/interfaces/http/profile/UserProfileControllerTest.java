package com.calles.platform.user.interfaces.http.profile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.user.application.profile.UserProfileApplicationService;
import com.calles.platform.user.application.security.UserAccessPolicy;
import com.calles.platform.user.interfaces.http.advice.UserExceptionHandler;
import com.calles.platform.user.interfaces.http.profile.dto.ProfileResponses;
import com.calles.platform.user.interfaces.http.profile.dto.UserProfilePatchRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 用户前台个人资料 HTTP 控制器端点测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileController HTTP 接口契约测试")
class UserProfileControllerTest {

    private static final String CURRENT_USER_ID = "user_me_0001";
    private static final String TARGET_USER_ID = "user_target_0002";

    @Mock
    private UserProfileApplicationService service;
    @Mock
    private UserAccessPolicy accessPolicy;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserProfileController controller = new UserProfileController(service, accessPolicy);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new UserExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/users/me 查询本人资料成功返回 200")
    void getMeSuccess() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(new UserInfo(CURRENT_USER_ID, "USER", "user", "sess-1"));
        ProfileResponses.Me me = new ProfileResponses.Me(
                CURRENT_USER_ID, "COMPLETED", 1L, "Tester", "avatar.png", "bio", "City", (byte) 1, null, null, null);
        when(service.getMe(CURRENT_USER_ID)).thenReturn(me);

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accountId").value(CURRENT_USER_ID))
                .andExpect(jsonPath("$.data.nickname").value("Tester"));
    }

    @Test
    @DisplayName("PATCH /api/users/me 修改本人资料成功返回 200")
    void patchMeSuccess() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(new UserInfo(CURRENT_USER_ID, "USER", "user", "sess-1"));
        ProfileResponses.Me updated = new ProfileResponses.Me(
                CURRENT_USER_ID, "COMPLETED", 2L, "NewNick", "avatar.png", "bio", "City", (byte) 1, null, null, null);
        when(service.patchMe(eq(CURRENT_USER_ID), any(UserProfilePatchRequest.class))).thenReturn(updated);

        mockMvc.perform(patch("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"NewNick\",\"revision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.nickname").value("NewNick"))
                .andExpect(jsonPath("$.data.revision").value(2));
    }

    @Test
    @DisplayName("GET /api/users/{accountId} 获取公开名片返回 200")
    void getPublicSuccess() throws Exception {
        ProfileResponses.Public pub = new ProfileResponses.Public(
                TARGET_USER_ID, "Creator", "avatar.png", "Creator bio");
        when(service.getPublic(TARGET_USER_ID)).thenReturn(pub);

        mockMvc.perform(get("/api/users/{accountId}", TARGET_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accountId").value(TARGET_USER_ID))
                .andExpect(jsonPath("$.data.nickname").value("Creator"));
    }

    @Test
    @DisplayName("POST /api/users/batch 批量获取公开名片返回 200")
    void batchSuccess() throws Exception {
        ProfileResponses.BatchItem item = new ProfileResponses.BatchItem(
                TARGET_USER_ID, true, new ProfileResponses.Summary(TARGET_USER_ID, "Creator", "avatar.png"));
        when(service.batchPublic(List.of(TARGET_USER_ID))).thenReturn(List.of(item));

        mockMvc.perform(post("/api/users/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountIds\":[\"" + TARGET_USER_ID + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].accountId").value(TARGET_USER_ID))
                .andExpect(jsonPath("$.data[0].available").value(true));
    }
}
