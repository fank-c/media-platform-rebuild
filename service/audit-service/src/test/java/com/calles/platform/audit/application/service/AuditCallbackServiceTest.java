package com.calles.platform.audit.application.service;

import com.calles.platform.audit.application.client.ContentServiceClient;
import com.calles.platform.audit.domain.model.enums.AuditResult;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.CallbackStatus;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import com.calles.platform.common.core.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditCallbackServiceTest {

    @Mock
    private ContentServiceClient contentServiceClient;

    @Mock
    private AuditTaskRepository auditTaskRepository;

    private AuditCallbackService callbackService;

    @BeforeEach
    void setUp() {
        callbackService = new AuditCallbackService(contentServiceClient, auditTaskRepository);
    }

    @Test
    @DisplayName("远程回调成功返回 200 时，任务标记为 SUCCESS")
    void callbackSuccessShouldUpdateStatus() {
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_100", "cv_test1", "u_001", "标题", "简介", "f_cover", "f_video"
        );
        task.startMachineAudit();
        task.completeMachineAudit(ReviewLevel.NORMAL, null);

        when(contentServiceClient.notifyAuditResult(any())).thenReturn(ApiResponse.ok());

        boolean result = callbackService.callbackContentService(task);

        assertThat(result).isTrue();
        assertThat(task.getCallbackStatus()).isEqualTo(CallbackStatus.SUCCESS);
        verify(auditTaskRepository).updateById(task);
    }

    @Test
    @DisplayName("远程回调抛出网络异常时，任务标记为 FAILED 并自增重试计数")
    void callbackExceptionShouldMarkFailed() {
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_100", "cv_test1", "u_001", "违规标题", "简介", "f_cover", "f_video"
        );
        task.startMachineAudit();
        task.completeMachineAudit(ReviewLevel.ILLEGAL, "违规内容");

        when(contentServiceClient.notifyAuditResult(any()))
                .thenThrow(new RuntimeException("Connect Timeout"));

        boolean result = callbackService.callbackContentService(task);

        assertThat(result).isFalse();
        assertThat(task.getCallbackStatus()).isEqualTo(CallbackStatus.FAILED);
        assertThat(task.getCallbackRetries()).isEqualTo(1);
        verify(auditTaskRepository).updateById(task);
    }

    @Test
    @DisplayName("任务尚未完结（PENDING）时，拒绝执行回调")
    void pendingTaskShouldNotCallback() {
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_100", "cv_test1", "u_001", "标题", "简介", "f_cover", "f_video"
        );

        boolean result = callbackService.callbackContentService(task);

        assertThat(result).isFalse();
        assertThat(task.getCallbackStatus()).isEqualTo(CallbackStatus.PENDING);
    }
}
