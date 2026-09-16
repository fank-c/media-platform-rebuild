package com.calles.platform.audit.interfaces.http.controller;

import com.calles.platform.audit.application.service.AliyunAuditCallbackApplicationService;
import com.calles.platform.audit.interfaces.http.advice.AuditExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阿里云视频机审回调控制器 (AliyunAuditCallbackController) 接口测试。
 */
@WebMvcTest(AliyunAuditCallbackController.class)
@Import(AuditExceptionHandler.class)
class AliyunAuditCallbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AliyunAuditCallbackApplicationService callbackService;

    @Test
    @DisplayName("正常接收回调返回 HTTP 200 与 success 响应")
    void shouldReturn200OnValidCallback() throws Exception {
        String checksum = "valid_checksum_hash";
        String content = "{\"dataId\":\"task_100\",\"riskLevel\":\"none\"}";

        doNothing().when(callbackService).handleVideoCallback(eq(checksum), eq(content));

        mockMvc.perform(post("/api/audit/callback/aliyun/video")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("checksum", checksum)
                        .param("content", content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("success"));
    }

    @Test
    @DisplayName("签名非法时抛出异常并返回 400 客户端错误")
    void shouldReturn400OnInvalidChecksum() throws Exception {
        String checksum = "invalid_hash";
        String content = "{\"dataId\":\"task_100\"}";

        doThrow(new IllegalArgumentException("回调签名非法，拒绝消费"))
                .when(callbackService).handleVideoCallback(eq(checksum), eq(content));

        mockMvc.perform(post("/api/audit/callback/aliyun/video")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("checksum", checksum)
                        .param("content", content))
                .andExpect(status().isBadRequest());
    }
}
