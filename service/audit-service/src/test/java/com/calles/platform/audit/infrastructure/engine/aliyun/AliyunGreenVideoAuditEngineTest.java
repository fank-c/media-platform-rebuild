package com.calles.platform.audit.infrastructure.engine.aliyun;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.VideoModerationRequest;
import com.aliyun.green20220302.models.VideoModerationResponse;
import com.aliyun.green20220302.models.VideoModerationResponseBody;
import com.aliyun.green20220302.models.VideoModerationResultRequest;
import com.aliyun.green20220302.models.VideoModerationResultResponse;
import com.aliyun.green20220302.models.VideoModerationResultResponseBody;
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
 * 阿里云视频机审引擎 (AliyunGreenVideoAuditEngine) 单元测试用例。
 */
@ExtendWith(MockitoExtension.class)
class AliyunGreenVideoAuditEngineTest {

    @Mock
    private Client client;

    @Mock
    private FileServiceClient fileServiceClient;

    private AliyunGreenProperties properties;
    private ObjectMapper objectMapper;
    private AliyunGreenVideoAuditEngine engine;

    @BeforeEach
    void setUp() {
        properties = new AliyunGreenProperties();
        properties.setEnabled(true);
        properties.setVideoService("video_detection");
        properties.setPollIntervalMillis(10);
        properties.setPollTimeoutSeconds(1);
        properties.setShortProbeTimeoutSeconds(1);
        objectMapper = new ObjectMapper();
        engine = new AliyunGreenVideoAuditEngine(client, properties, fileServiceClient, objectMapper);
    }

    @Test
    @DisplayName("主视频文件为空时直接判定为不通过")
    void shouldRejectWhenVideoFileIdIsBlank() {
        EngineAuditResult result = engine.auditVideo("", "user_1", "task_1");

        assertThat(result.dimension()).isEqualTo(AuditDimension.VIDEO);
        assertThat(result.level()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(result.hitWords()).contains("MISSING_VIDEO_FILE");
    }

    @Test
    @DisplayName("轨道A：无回调地址时主动轮询成功返回 NORMAL")
    void shouldReturnNormalOnPollingSuccess() throws Exception {
        when(fileServiceClient.getDownloadUrl(eq("video_123"), eq("user_1"), eq("USER")))
                .thenReturn(ApiResponse.ok(new FileDownloadUrlDTO("http://minio.test/video.mp4", Instant.now().plusSeconds(300))));

        // 模拟提交任务响应
        VideoModerationResponseBody submitBody = new VideoModerationResponseBody();
        submitBody.setCode(200);
        VideoModerationResponseBody.VideoModerationResponseBodyData submitData = new VideoModerationResponseBody.VideoModerationResponseBodyData();
        submitData.setTaskId("aliyun_v_100");
        submitBody.setData(submitData);
        VideoModerationResponse submitResponse = new VideoModerationResponse();
        submitResponse.setBody(submitBody);

        when(client.videoModeration(any(VideoModerationRequest.class))).thenReturn(submitResponse);

        // 模拟轮询查询结果响应
        VideoModerationResultResponseBody queryBody = new VideoModerationResultResponseBody();
        queryBody.setCode(200);
        VideoModerationResultResponseBody.VideoModerationResultResponseBodyData queryData = new VideoModerationResultResponseBody.VideoModerationResultResponseBodyData();
        VideoModerationResultResponseBody.VideoModerationResultResponseBodyDataFrameResult frameResult = new VideoModerationResultResponseBody.VideoModerationResultResponseBodyDataFrameResult();
        frameResult.setFrameSummarys(List.of());
        queryData.setFrameResult(frameResult);
        queryBody.setData(queryData);
        VideoModerationResultResponse queryResponse = new VideoModerationResultResponse();
        queryResponse.setBody(queryBody);

        when(client.videoModerationResult(any(VideoModerationResultRequest.class))).thenReturn(queryResponse);

        EngineAuditResult result = engine.auditVideo("video_123", "user_1", "task_1");

        assertThat(result.level()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(result.detailLog()).contains("合规正常");
    }

    @Test
    @DisplayName("轨道A：主动轮询超时自动降级为疑似人工审核")
    void shouldDegradeWhenPollingTimesOut() throws Exception {
        when(fileServiceClient.getDownloadUrl(eq("video_123"), eq("user_1"), eq("USER")))
                .thenReturn(ApiResponse.ok(new FileDownloadUrlDTO("http://minio.test/video.mp4", Instant.now().plusSeconds(300))));

        VideoModerationResponseBody submitBody = new VideoModerationResponseBody();
        submitBody.setCode(200);
        VideoModerationResponseBody.VideoModerationResponseBodyData submitData = new VideoModerationResponseBody.VideoModerationResponseBodyData();
        submitData.setTaskId("aliyun_v_100");
        submitBody.setData(submitData);
        VideoModerationResponse submitResponse = new VideoModerationResponse();
        submitResponse.setBody(submitBody);

        when(client.videoModeration(any(VideoModerationRequest.class))).thenReturn(submitResponse);

        // 轮询返回尚未就绪
        VideoModerationResultResponseBody queryBody = new VideoModerationResultResponseBody();
        queryBody.setCode(200);
        VideoModerationResultResponseBody.VideoModerationResultResponseBodyData emptyData = new VideoModerationResultResponseBody.VideoModerationResultResponseBodyData();
        queryBody.setData(emptyData);
        VideoModerationResultResponse queryResponse = new VideoModerationResultResponse();
        queryResponse.setBody(queryBody);

        when(client.videoModerationResult(any(VideoModerationResultRequest.class))).thenReturn(queryResponse);

        EngineAuditResult result = engine.auditVideo("video_123", "user_1", "task_1");

        assertThat(result.level()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(result.hitWords()).contains("VIDEO_POLL_TIMEOUT");
    }

    @Test
    @DisplayName("轨道B：配置回调地址时短时探测未完结则返回中间态等待 Webhook")
    void shouldReturnAsyncInProgressWhenCallbackConfigured() throws Exception {
        properties.setCallbackUrl("https://api.test/api/audit/callback/aliyun/video");
        properties.setCallbackSeed("test_seed");

        when(fileServiceClient.getDownloadUrl(eq("video_123"), eq("user_1"), eq("USER")))
                .thenReturn(ApiResponse.ok(new FileDownloadUrlDTO("http://minio.test/video.mp4", Instant.now().plusSeconds(300))));

        VideoModerationResponseBody submitBody = new VideoModerationResponseBody();
        submitBody.setCode(200);
        VideoModerationResponseBody.VideoModerationResponseBodyData submitData = new VideoModerationResponseBody.VideoModerationResponseBodyData();
        submitData.setTaskId("aliyun_v_200");
        submitBody.setData(submitData);
        VideoModerationResponse submitResponse = new VideoModerationResponse();
        submitResponse.setBody(submitBody);

        when(client.videoModeration(any(VideoModerationRequest.class))).thenReturn(submitResponse);

        // 3秒内短探测未出结果
        VideoModerationResultResponseBody queryBody = new VideoModerationResultResponseBody();
        queryBody.setCode(200);
        queryBody.setData(new VideoModerationResultResponseBody.VideoModerationResultResponseBodyData());
        VideoModerationResultResponse queryResponse = new VideoModerationResultResponse();
        queryResponse.setBody(queryBody);

        when(client.videoModerationResult(any(VideoModerationResultRequest.class))).thenReturn(queryResponse);

        EngineAuditResult result = engine.auditVideo("video_123", "user_1", "task_1");

        assertThat(result.level()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(result.hitWords()).contains("ASYNC_IN_PROGRESS");
        assertThat(result.detailLog()).contains("aliyun_v_200");
    }
}
