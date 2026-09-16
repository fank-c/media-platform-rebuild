package com.calles.platform.audit.application.service.callback;

import com.calles.platform.audit.application.service.AuditCallbackService;
import com.calles.platform.audit.config.aliyun.AliyunGreenProperties;
import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.AuditResult;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.repository.AuditDetailRepository;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import com.calles.platform.audit.domain.service.AuditDecisionAggregator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阿里云异步 Webhook 回调核心用例 (AliyunAuditCallbackApplicationService) 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AliyunAuditCallbackApplicationServiceTest {

    @Mock
    private AuditTaskRepository auditTaskRepository;

    @Mock
    private AuditDetailRepository auditDetailRepository;

    @Mock
    private AuditCallbackService callbackService;

    private AliyunGreenProperties properties;
    private AuditDecisionAggregator decisionAggregator;
    private ObjectMapper objectMapper;
    private AliyunAuditCallbackApplicationService service;

    @BeforeEach
    void setUp() {
        properties = new AliyunGreenProperties();
        properties.setUid("12345678");
        properties.setCallbackSeed("secret_seed_999");
        decisionAggregator = new AuditDecisionAggregator();
        objectMapper = new ObjectMapper();
        service = new AliyunAuditCallbackApplicationService(
                properties,
                auditTaskRepository,
                auditDetailRepository,
                decisionAggregator,
                callbackService,
                objectMapper
        );
    }

    private String calculateChecksum(String content) throws Exception {
        String target = properties.getUid() + properties.getCallbackSeed() + content;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(target.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("验签失败时直接抛出 IllegalArgumentException")
    void shouldThrowExceptionWhenChecksumIsInvalid() {
        String content = "{\"dataId\":\"task_100\",\"riskLevel\":\"high\"}";

        assertThatThrownBy(() -> service.handleVideoCallback("invalid_checksum", content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("回调签名非法");

        verify(auditTaskRepository, never()).findById(any());
    }

    @Test
    @DisplayName("正常回调判定为放行时：流转至 FINISHED / PASSED 并通知内容服务")
    void shouldCompleteAndCallbackWhenRiskIsNone() throws Exception {
        AuditTask task = AuditTask.createVideoAuditTask(
                "video_1", "cv100", "author_1", "Title", "Desc", "cover_1", "video_1");
        task.startMachineAudit();
        task.completeMachineAudit(ReviewLevel.SUSPICIOUS, "等待异步回调");
        String taskId = task.getId();

        String content = "{\"DataId\":\"" + taskId + "\",\"TaskId\":\"aliyun_v_1\",\"RiskLevel\":\"none\"}";
        String checksum = calculateChecksum(content);

        when(auditTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(auditDetailRepository.findByTaskId(taskId)).thenReturn(List.of(
                AuditDetail.of(taskId, AuditDimension.TEXT, "LOCAL_DFA", ReviewLevel.NORMAL, BigDecimal.valueOf(100), null, "正常"),
                AuditDetail.of(taskId, AuditDimension.IMAGE, "ALIYUN_GREEN_IMAGE", ReviewLevel.NORMAL, BigDecimal.valueOf(100), null, "正常"),
                AuditDetail.of(taskId, AuditDimension.VIDEO, "ALIYUN_GREEN_VIDEO", ReviewLevel.NORMAL, BigDecimal.valueOf(100), null, "正常")
        ));

        service.handleVideoCallback(checksum, content);

        assertThat(task.getStage()).isEqualTo(AuditStage.FINISHED);
        assertThat(task.getResult()).isEqualTo(AuditResult.PASSED);

        verify(auditTaskRepository).updateById(task);
        verify(callbackService).callbackContentService(task);
    }

    @Test
    @DisplayName("严重违规回调时：流转至 FINISHED / REJECTED 并通知内容服务")
    void shouldRejectWhenRiskIsHigh() throws Exception {
        AuditTask task = AuditTask.createVideoAuditTask(
                "video_2", "cv200", "author_1", "Title", "Desc", "cover_1", "video_2");
        task.startMachineAudit();
        String taskId = task.getId();

        String content = "{\"DataId\":\"" + taskId + "\",\"TaskId\":\"aliyun_v_2\",\"RiskLevel\":\"high\",\"Result\":[{\"Label\":\"porn\",\"Suggestion\":\"block\"}]}";
        String checksum = calculateChecksum(content);

        when(auditTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(auditDetailRepository.findByTaskId(taskId)).thenReturn(List.of(
                AuditDetail.of(taskId, AuditDimension.VIDEO, "ALIYUN_GREEN_VIDEO", ReviewLevel.ILLEGAL, BigDecimal.valueOf(100), "porn", "违规")
        ));

        service.handleVideoCallback(checksum, content);

        assertThat(task.getStage()).isEqualTo(AuditStage.FINISHED);
        assertThat(task.getResult()).isEqualTo(AuditResult.REJECTED);
        assertThat(task.getRejectReason()).contains("违规");

        verify(callbackService).callbackContentService(task);
    }

    @Test
    @DisplayName("任务已终局归档时幂等忽略回调通知")
    void shouldIgnoreWhenTaskAlreadyFinished() throws Exception {
        AuditTask task = AuditTask.createVideoAuditTask(
                "video_3", "cv300", "author_1", "Title", "Desc", "cover_1", "video_3");
        task.startMachineAudit();
        task.completeMachineAudit(ReviewLevel.NORMAL, "已放行");
        String taskId = task.getId();

        String content = "{\"DataId\":\"" + taskId + "\",\"TaskId\":\"aliyun_v_3\",\"RiskLevel\":\"none\"}";
        String checksum = calculateChecksum(content);

        when(auditTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        service.handleVideoCallback(checksum, content);

        verify(auditDetailRepository, never()).insert(any());
        verify(callbackService, never()).callbackContentService(task);
    }
}
