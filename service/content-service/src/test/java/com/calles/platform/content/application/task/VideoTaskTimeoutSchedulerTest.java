package com.calles.platform.content.application.task;

import com.calles.platform.content.domain.model.task.TaskStatus;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.task.VideoTask;
import com.calles.platform.content.domain.repository.VideoTaskRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频异步处理任务超时巡检与重试补偿调度器 {@link VideoTaskTimeoutScheduler} 单元测试。
 *
 * <p>验证超时任务扫描、可重试任务自愈状态重置、达到重试上限后的 FAILED 终态流转及 AUDIT 审核超时触发的级联驳回。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoTaskTimeoutScheduler 超时巡检调度测试")
class VideoTaskTimeoutSchedulerTest {

    /** 模拟任务仓储。 */
    @Mock
    private VideoTaskRepository videoTaskRepository;

    /** 模拟发布门禁决策器。 */
    @Mock
    private PublishGatekeeper publishGatekeeper;

    /** 待测试的超时巡检调度器。 */
    @InjectMocks
    private VideoTaskTimeoutScheduler scheduler;

    /**
     * 测试巡检发现执行超时任务且该任务尚未达到最大重试上限时，自愈重置为 PENDING 并递增重试计数。
     */
    @Test
    @DisplayName("巡检发现超时任务且未达重试上限 -> 触发重试重置为 PENDING")
    void shouldRetryTimedOutTaskUnderLimit() {
        // 步骤 1 (Given)：创建任务并模拟失败后正在重新执行 (RUNNING)
        VideoTask task = VideoTask.create("v_100", TaskType.TRANSCODE_720P);
        task.start(); // status = RUNNING, retryCount = 0, maxRetries = 3
        task.fail("临时失败");
        task.start(); // RUNNING

        when(videoTaskRepository.findTimeoutTasks(eq(TaskStatus.RUNNING), any(LocalDateTime.class)))
                .thenReturn(List.of(task));

        // 步骤 2 (When)：执行超时巡检与自愈补偿
        int recovered = scheduler.inspectAndRecoverTimeouts();

        // 步骤 3 (Then)：断言自愈补偿成功，任务重置为 PENDING 且重试计数自增
        assertThat(recovered).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getRetryCount()).isEqualTo(1);
        verify(videoTaskRepository).updateById(task);
    }

    /**
     * 测试巡检发现超时任务但重试次数已达最大阈值时，将其置为 FAILED，且阻断性 AUDIT 任务联动门禁终止流水线。
     */
    @Test
    @DisplayName("巡检发现超时任务且已达重试上限 -> 标记为 FAILED 且若为 AUDIT 联动驳回")
    void shouldFailTaskAndRejectWhenExceededMaxRetries() {
        // 步骤 1 (Given)：创建 AUDIT 任务并使其达到最大重试限制 1
        VideoTask task = VideoTask.create("v_100", TaskType.AUDIT);
        task.setMaxRetries(1);
        task.fail("一次失败");
        task.markForRetry(); // retryCount = 1
        task.start(); // RUNNING, canRetry is false

        when(videoTaskRepository.findTimeoutTasks(eq(TaskStatus.RUNNING), any(LocalDateTime.class)))
                .thenReturn(List.of(task));

        // 步骤 2 (When)：执行超时巡检
        int recovered = scheduler.inspectAndRecoverTimeouts();

        // 步骤 3 (Then)：断言补偿标记为 FAILED，错误信息记录超限，且联动驳回了全流水线
        assertThat(recovered).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getErrorMessage()).contains("任务执行超时且已达最大重试上限");
        verify(videoTaskRepository).updateById(task);
        verify(publishGatekeeper).rejectAndCancelPipeline("v_100", "审核任务超时失败");
    }

    /**
     * 测试当无任何超时任务时，巡检平稳结束且返回补偿计数 0。
     */
    @Test
    @DisplayName("无超时任务时巡检返回 0")
    void shouldReturnZeroWhenNoTimeouts() {
        // 步骤 1 (Given)：模拟无任何超时记录
        when(videoTaskRepository.findTimeoutTasks(eq(TaskStatus.RUNNING), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        // 步骤 2 (When)：执行巡检
        int recovered = scheduler.inspectAndRecoverTimeouts();

        // 步骤 3 (Then)：断言返回 0
        assertThat(recovered).isZero();
    }
}
