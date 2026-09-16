package com.calles.platform.audit.infrastructure.engine.aliyun;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.ImageModerationRequest;
import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.ImageModerationResponseBody;
import com.calles.platform.audit.application.client.FileServiceClient;
import com.calles.platform.audit.application.client.dto.FileDownloadUrlDTO;
import com.calles.platform.audit.config.aliyun.AliyunGreenProperties;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.common.core.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 阿里云图像机审引擎 (AliyunGreenImageAuditEngine) 单元测试用例。
 */
@ExtendWith(MockitoExtension.class)
class AliyunGreenImageAuditEngineTest {

    @Mock
    private Client client;

    @Mock
    private FileServiceClient fileServiceClient;

    private AliyunGreenProperties properties;
    private ObjectMapper objectMapper;
    private AliyunGreenImageAuditEngine engine;

    @BeforeEach
    void setUp() {
        properties = new AliyunGreenProperties();
        properties.setEnabled(true);
        properties.setImageService("baselineCheck");
        objectMapper = new ObjectMapper();
        engine = new AliyunGreenImageAuditEngine(client, properties, fileServiceClient, objectMapper);
    }

    @Test
    @DisplayName("封面文件为空时直接判定为不通过")
    void shouldRejectWhenCoverFileIdIsBlank() {
        EngineAuditResult result = engine.auditCover("", "user_1", "task_1");

        assertThat(result.dimension()).isEqualTo(AuditDimension.IMAGE);
        assertThat(result.level()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(result.hitWords()).contains("MISSING_COVER");
    }

    @Test
    @DisplayName("无法获取预签名直链时优雅降级为人工复审")
    void shouldDegradeWhenPresignUrlFails() {
        when(fileServiceClient.getDownloadUrl(eq("file_123"), eq("user_1"), eq("USER")))
                .thenReturn(ApiResponse.ok(null));

        EngineAuditResult result = engine.auditCover("file_123", "user_1", "task_1");

        assertThat(result.level()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(result.hitWords()).contains("COVER_URL_UNAVAILABLE");
    }

    @Test
    @DisplayName("阿里云检测合规时返回 NORMAL")
    void shouldReturnNormalWhenAliyunApproves() throws Exception {
        when(fileServiceClient.getDownloadUrl(eq("file_123"), eq("user_1"), eq("USER")))
                .thenReturn(ApiResponse.ok(new FileDownloadUrlDTO("http://minio.test/file_123", Instant.now().plusSeconds(300))));

        ImageModerationResponseBody body = new ImageModerationResponseBody();
        body.setCode(200);
        ImageModerationResponseBody.ImageModerationResponseBodyData data = new ImageModerationResponseBody.ImageModerationResponseBodyData();
        data.setDataId("task_1");
        data.setResult(List.of());
        body.setData(data);

        ImageModerationResponse response = new ImageModerationResponse();
        response.setBody(body);

        when(client.imageModeration(any(ImageModerationRequest.class))).thenReturn(response);

        EngineAuditResult result = engine.auditCover("file_123", "user_1", "task_1");

        assertThat(result.level()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(result.detailLog()).contains("合规正常");
    }

    @Test
    @DisplayName("阿里云命中严重违规时返回 ILLEGAL")
    void shouldReturnIllegalWhenAliyunDetectsHighRisk() throws Exception {
        when(fileServiceClient.getDownloadUrl(eq("file_123"), eq("user_1"), eq("USER")))
                .thenReturn(ApiResponse.ok(new FileDownloadUrlDTO("http://minio.test/file_123", Instant.now().plusSeconds(300))));

        ImageModerationResponseBody body = new ImageModerationResponseBody();
        body.setCode(200);
        ImageModerationResponseBody.ImageModerationResponseBodyData data = new ImageModerationResponseBody.ImageModerationResponseBodyData();
        data.setRiskLevel("high");
        ImageModerationResponseBody.ImageModerationResponseBodyDataResult resItem = new ImageModerationResponseBody.ImageModerationResponseBodyDataResult();
        resItem.setLabel("porn");
        resItem.setConfidence(99.5f);
        data.setResult(List.of(resItem));
        body.setData(data);

        ImageModerationResponse response = new ImageModerationResponse();
        response.setBody(body);

        when(client.imageModeration(any(ImageModerationRequest.class))).thenReturn(response);

        EngineAuditResult result = engine.auditCover("file_123", "user_1", "task_1");

        assertThat(result.level()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(result.hitWords()).contains("porn");
    }

    @Test
    @DisplayName("阿里云通信抛出异常时优雅降级为 SUSPICIOUS")
    void shouldDegradeWhenClientThrowsException() throws Exception {
        when(fileServiceClient.getDownloadUrl(eq("file_123"), eq("user_1"), eq("USER")))
                .thenReturn(ApiResponse.ok(new FileDownloadUrlDTO("http://minio.test/file_123", Instant.now().plusSeconds(300))));

        when(client.imageModeration(any(ImageModerationRequest.class)))
                .thenThrow(new RuntimeException("Connection timeout to Aliyun"));

        EngineAuditResult result = engine.auditCover("file_123", "user_1", "task_1");

        assertThat(result.level()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(result.hitWords()).contains("ALIYUN_CALL_EXCEPTION");
    }
}
