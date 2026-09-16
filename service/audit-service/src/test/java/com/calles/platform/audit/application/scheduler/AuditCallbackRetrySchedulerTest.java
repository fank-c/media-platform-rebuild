package com.calles.platform.audit.application.scheduler;

import com.calles.platform.audit.application.service.AuditCallbackService;
import com.calles.platform.audit.domain.model.enums.AuditResult;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.CallbackStatus;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditCallbackRetrySchedulerTest {

    @Mock
    private AuditTaskRepository auditTaskRepository;

    @Mock
    private AuditCallbackService callbackService;

    private AuditCallbackRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AuditCallbackRetryScheduler(auditTaskRepository, callbackService);
        ReflectionTestUtils.setField(scheduler, "maxRetries", 3);
    }

    @Test
    @DisplayName("扫描待补偿的失败任务并逐个调用回调重试服务")
    void shouldRetryFailedTasks() {
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_100", "cv_test", "u_001", "标题", null, "c", "v"
        );
        task.startMachineAudit();
        task.completeMachineAudit(ReviewLevel.NORMAL, null);
        task.markCallbackFailed();

        when(auditTaskRepository.findPendingCallbacks(eq(CallbackStatus.FAILED), eq(3), anyInt()))
                .thenReturn(List.of(task));

        scheduler.retryPendingCallbacks();

        verify(callbackService).callbackContentService(task);
    }
}
