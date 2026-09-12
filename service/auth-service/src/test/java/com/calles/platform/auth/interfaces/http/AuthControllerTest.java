package com.calles.platform.auth.interfaces.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.calles.platform.auth.application.AuthService;
import com.calles.platform.auth.domain.account.AccountRole;
import com.calles.platform.auth.exception.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 认证 HTTP 契约测试。重点锁定 refresh 不要求 Authorization，以及字段校验和认证失败保持既有状态码与
 * {@code ApiResponse.data=null} 语义。
 */
class AuthControllerTest {

    /** 独立 MVC 入口，不启动 Redis、数据库或完整 Spring 上下文。 */
    private MockMvc mockMvc;
    /** 认证应用服务替身，用于隔离 HTTP 层协议断言。 */
    private AuthService authService;

    /** 每个用例构造独立控制器，避免 Mock 调用记录跨用例污染。 */
    @BeforeEach
    void setUp() {
        authService = Mockito.mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new AuthExceptionHandler())
                .build();
    }

    /** refresh 只使用请求体凭据；不携带 Authorization 仍可按既有契约成功轮换。 */
    @Test
    void refreshWithoutAuthorizationReturnsExistingTokenResponse() throws Exception {
        when(authService.refresh("refresh-value"))
                .thenReturn(new AuthService.AuthTokens("access-value", "new-refresh", 900, AccountRole.USER));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-value\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("access-value"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    /** 空 Refresh Token 必须在 HTTP 校验层返回 400，不调用应用层或 Redis。 */
    @Test
    void blankRefreshTokenReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** 已消费、注销或竞争失败的凭据保持 401 和空 data，不暴露 Redis 脚本内部状态。 */
    @Test
    void invalidRefreshTokenReturnsUnauthorizedWithNullData() throws Exception {
        when(authService.refresh("used-refresh")).thenThrow(AuthException.invalidRefreshToken());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"used-refresh\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** Redis 结果未知或脚本结构异常必须失败关闭为 503。 */
    @Test
    void unavailableSessionReturnsServiceUnavailableWithNullData() throws Exception {
        when(authService.refresh("refresh-value")).thenThrow(AuthException.sessionUnavailable());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-value\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** 登录请求携带合法邮箱必须成功映射并返回令牌。 */
    @Test
    void loginWithValidEmailReturnsTokens() throws Exception {
        when(authService.login(eq("user@example.com"), eq("password123"), any()))
                .thenReturn(new AuthService.AuthTokens("access-token", "refresh-token", 900, AccountRole.USER));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    /** 登录请求邮箱格式不合法必须在 DTO 校验层返回 400。 */
    @Test
    void loginWithInvalidEmailReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 注册请求携带合法邮箱必须成功并返回包含 email 的注册响应。 */
    @Test
    void registerWithValidEmailReturnsRegisterResponse() throws Exception {
        when(authService.register("new-user@example.com", "password123"))
                .thenReturn(new com.calles.platform.auth.interfaces.http.dto.RegisterResponse(
                        "acc-1", "new-user@example.com", "USER", "ACTIVE"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new-user@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accountId").value("acc-1"))
                .andExpect(jsonPath("$.data.email").value("new-user@example.com"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    /** 注册请求邮箱格式不合法必须在 DTO 校验层返回 400。 */
    @Test
    void registerWithInvalidEmailReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid-format\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
