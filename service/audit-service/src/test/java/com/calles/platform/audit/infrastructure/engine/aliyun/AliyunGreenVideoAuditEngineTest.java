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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    /** 使用生产默认 Service 和模拟客户端初始化引擎，避免测试覆盖掩盖默认配置错误。 */
    @BeforeEach
    void setUp() {
        properties = new AliyunGreenProperties();
        properties.setEnabled(true);
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

    /** 验证提交任务即返回异步处理中状态，且正确使用 videoDetection Service，零线程阻塞。 */
    @Test
    @DisplayName("非阻塞提审：使用正确默认 Service 提交并立即返回 ASYNC_IN_PROGRESS")
    void shouldReturnAsyncInProgressOnSubmit() throws Exception {
        when(fileServiceClient.getDownloadUrl(eq("video_123"), eq("user_1"), eq("USER")))
                .thenReturn(ApiResponse.ok(new FileDownloadUrlDTO("http://oss.test/video.mp4", Instant.now().plusSeconds(300))));

        // 模拟提交任务响应
        VideoModerationResponseBody submitBody = new VideoModerationResponseBody();
        submitBody.setCode(200);
        VideoModerationResponseBody.VideoModerationResponseBodyData submitData = new VideoModerationResponseBody.VideoModerationResponseBodyData();
        submitData.setTaskId("aliyun_v_100");
        submitBody.setData(submitData);
        VideoModerationResponse submitResponse = new VideoModerationResponse();
        submitResponse.setBody(submitBody);

        when(client.videoModeration(any(VideoModerationRequest.class))).thenReturn(submitResponse);

        EngineAuditResult result = engine.auditVideo("video_123", "user_1", "task_1");

        // 断言提交即返回进行中，包含 taskId 与截止时间
        assertThat(result.dimension()).isEqualTo(AuditDimension.VIDEO);
        assertThat(result.hitWords()).contains("ASYNC_IN_PROGRESS");
        assertThat(result.detailLog()).contains("ALIYUN_TASK_ID:aliyun_v_100");
        assertThat(result.detailLog()).contains("DEADLINE:");

        // 校验真正提交的请求 Service 是否与契约一致
        ArgumentCaptor<VideoModerationRequest> requestCaptor = ArgumentCaptor.forClass(VideoModerationRequest.class);
        verify(client).videoModeration(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getService()).isEqualTo("videoDetection");
    }

    @Test
    @DisplayName("探针查询：云端完成时单次查询返回合规 NORMAL 明细")
    void shouldReturnNormalOnQuerySuccess() throws Exception {
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

        EngineAuditResult result = engine.queryVideoModerationResult("aliyun_v_100");

        assertThat(result).isNotNull();
        assertThat(result.level()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(result.detailLog()).contains("合规正常");
    }

    @Test
    @DisplayName("探针查询：云端未就绪时单次查询返回 null 供定时任务继续等待")
    void shouldReturnNullWhenQueryStillInProgress() throws Exception {
        VideoModerationResultResponseBody queryBody = new VideoModerationResultResponseBody();
        queryBody.setCode(200);
        queryBody.setData(new VideoModerationResultResponseBody.VideoModerationResultResponseBodyData());
        VideoModerationResultResponse queryResponse = new VideoModerationResultResponse();
        queryResponse.setBody(queryBody);

        when(client.videoModerationResult(any(VideoModerationResultRequest.class))).thenReturn(queryResponse);

        EngineAuditResult result = engine.queryVideoModerationResult("aliyun_v_100");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("轻量轮询：主动轮询超时自动降级为疑似人工审核")
    void shouldDegradeWhenPollingTimesOut() throws Exception {
        VideoModerationResultResponseBody queryBody = new VideoModerationResultResponseBody();
        queryBody.setCode(200);
        queryBody.setData(new VideoModerationResultResponseBody.VideoModerationResultResponseBodyData());
        VideoModerationResultResponse queryResponse = new VideoModerationResultResponse();
        queryResponse.setBody(queryBody);

        when(client.videoModerationResult(any(VideoModerationResultRequest.class))).thenReturn(queryResponse);

        EngineAuditResult result = engine.pollForVideoResult("aliyun_v_100", 1, 10);

        assertThat(result.level()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(result.hitWords()).contains("VIDEO_POLL_TIMEOUT");
    }
}
