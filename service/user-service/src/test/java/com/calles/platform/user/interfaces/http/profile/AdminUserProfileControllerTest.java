package com.calles.platform.user.interfaces.http.profile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
 * 管理端用户资料 HTTP 控制器端点测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserProfileController HTTP 接口契约测试")
class AdminUserProfileControllerTest {

    private static final String ADMIN_ID = "admin_root_001";
    private static final String TARGET_USER_ID = "user_target_0002";

    @Mock
    private UserProfileApplicationService service;
    @Mock
    private UserAccessPolicy accessPolicy;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminUserProfileController controller = new AdminUserProfileController(service, accessPolicy);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new UserExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/users/admin/list 管理员分页检索用户资料返回 200")
    void listAdminSuccess() throws Exception {
        when(accessPolicy.requireAdmin()).thenReturn(new UserInfo(ADMIN_ID, "ADMIN", "admin", "adm-sess-1"));
        ProfileResponses.Admin row = new ProfileResponses.Admin(
                TARGET_USER_ID, 1L, "TargetUser", "avatar.png", "bio", "City", (byte) 1, null, "ACTIVE", null, null);
        ProfileResponses.AdminPage page = new ProfileResponses.AdminPage(List.of(row), 1, 1, 20);
        when(service.listAdmin(any(), anyLong(), anyLong())).thenReturn(page);

        mockMvc.perform(post("/api/users/admin/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].accountId").value(TARGET_USER_ID))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("PATCH /api/users/admin/{accountId} 管理员修改用户资料成功返回 200")
    void patchAdminSuccess() throws Exception {
        when(accessPolicy.requireAdmin()).thenReturn(new UserInfo(ADMIN_ID, "ADMIN", "admin", "adm-sess-1"));
        ProfileResponses.Admin updated = new ProfileResponses.Admin(
                TARGET_USER_ID, 2L, "CorrectedNick", "avatar.png", "bio", "City", (byte) 1, null, "ACTIVE", null, null);
        when(service.patchAdmin(eq(TARGET_USER_ID), any(UserProfilePatchRequest.class))).thenReturn(updated);

        mockMvc.perform(patch("/api/users/admin/{accountId}", TARGET_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"CorrectedNick\",\"revision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accountId").value(TARGET_USER_ID))
                .andExpect(jsonPath("$.data.nickname").value("CorrectedNick"))
                .andExpect(jsonPath("$.data.revision").value(2));
    }
}
