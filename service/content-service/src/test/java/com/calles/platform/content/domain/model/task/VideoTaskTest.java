package com.calles.platform.content.domain.model.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 视频异步流水线子任务领域模型 {@link VideoTask} 状态机与生命周期单元测试。
 *
 * <p>覆盖任务初始化、状态流转 (RUNNING/SUCCESS/FAILED/CANCELED)、进度区间保护及多轮重试阈值限制。</p>
 */
@DisplayName("VideoTask 领域模型测试")
class VideoTaskTest {

    /**
     * 测试工厂方法能够正确构建初始处于排队状态 (PENDING) 的任务实体。
     */
    @Test
    @DisplayName("工厂方法创建初始 PENDING 任务")
    void shouldCreateInitialPendingTask() {
        // 步骤 1 (Given & When)：调用静态工厂方法初始化 AUDIT 审核任务
        VideoTask task = VideoTask.create("v_100", TaskType.AUDIT);

        // 步骤 2 (Then)：核验主键 ID、初始状态、重试上限与创建时间
        assertThat(task.getId()).isNotNull().hasSize(32);
        assertThat(task.getVideoId()).isEqualTo("v_100");
        assertThat(task.getTaskType()).isEqualTo(TaskType.AUDIT);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getProgress()).isZero();
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getMaxRetries()).isEqualTo(3);
        assertThat(task.getCreatedAt()).isNotNull();
    }

    /**
     * 测试任务从 PENDING 流转为 RUNNING，并核验启动时间戳记录。
     */
    @Test
    @DisplayName("任务启动状态流转为 RUNNING")
    void shouldStartTask() {
        // 步骤 1 (Given)：创建初始任务
        VideoTask task = VideoTask.create("v_100", TaskType.TRANSCODE_720P);

        // 步骤 2 (When)：触发任务启动
        task.start();

        // 步骤 3 (Then)：断言状态流转为 RUNNING 且记录 startedAt
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.getStartedAt()).isNotNull();
    }

    /**
     * 测试进度汇报能够自动将输入数值钳制在 [0, 100] 的有效区间内。
     */
    @Test
    @DisplayName("汇报进度限制在 0-100 区间")
    void shouldUpdateProgressClamped() {
        // 步骤 1 (Given)：启动一个 1080P 转码任务
        VideoTask task = VideoTask.create("v_100", TaskType.TRANSCODE_1080P);
        task.start();

        // 步骤 2 (When & Then)：汇报正常 50% 进度
        task.updateProgress(50);
        assertThat(task.getProgress()).isEqualTo(50);

        // 步骤 3 (When & Then)：汇报超出上限 150%，断言自动钳制为 100%
        task.updateProgress(150);
        assertThat(task.getProgress()).isEqualTo(100);

        // 步骤 4 (When & Then)：汇报负数 -10%，断言自动钳制为 0%
        task.updateProgress(-10);
        assertThat(task.getProgress()).isZero();
    }

    /**
     * 测试任务成功完成时的状态跃迁、进度置满与错误信息清理。
     */
    @Test
    @DisplayName("任务完成状态流转为 SUCCESS 且进度置 100")
    void shouldCompleteTask() {
        // 步骤 1 (Given)：创建并启动向量特征计算任务
        VideoTask task = VideoTask.create("v_100", TaskType.VECTOR_EMBEDDING);
        task.start();

        // 步骤 2 (When)：标记任务完成
        task.complete();

        // 步骤 3 (Then)：断言状态为 SUCCESS，进度自动设为 100%，错误信息为空
        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(task.getProgress()).isEqualTo(100);
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(task.getErrorMessage()).isNull();
    }

    /**
     * 测试任务执行失败时的状态流转与失败原因留存。
     */
    @Test
    @DisplayName("任务失败置 FAILED 且记录错误信息")
    void shouldFailTask() {
        // 步骤 1 (Given)：创建并启动任务
        VideoTask task = VideoTask.create("v_100", TaskType.AUDIT);
        task.start();

        // 步骤 2 (When)：触发任务失败并传入违规原因
        task.fail("违规敏感内容");

        // 步骤 3 (Then)：断言状态为 FAILED，错误说明正确沉淀
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getErrorMessage()).isEqualTo("违规敏感内容");
        assertThat(task.getCompletedAt()).isNotNull();
    }

    /**
     * 测试已进入 SUCCESS 终态的任务不可被逆向修改为 CANCELED。
     */
    @Test
    @DisplayName("已完成任务不可被取消")
    void shouldNotCancelCompletedTask() {
        // 步骤 1 (Given)：将任务置为 SUCCESS 终态
        VideoTask task = VideoTask.create("v_100", TaskType.TRANSCODE_720P);
        task.complete();

        // 步骤 2 (When)：尝试调用取消
        task.cancel("尝试取消");

        // 步骤 3 (Then)：断言状态依然保持 SUCCESS，防逆转生效
        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    }

    /**
     * 测试重试自愈机制：在未达到最大上限 3 次前允许重试，达到后抛出异常拒绝重试。
     */
    @Test
    @DisplayName("重试机制：未达上限允许重试并自增 retryCount")
    void shouldSupportRetryUnderLimit() {
        // 步骤 1 (Given)：创建 4K 转码任务并模拟执行失败
        VideoTask task = VideoTask.create("v_100", TaskType.TRANSCODE_4K);
        task.fail("FFmpeg 超时");

        // 步骤 2 (When)：第 1 次重试
        assertThat(task.canRetry()).isTrue();
        task.markForRetry();

        // 步骤 3 (Then)：状态恢复为 PENDING，重试计数递增为 1
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getRetryCount()).isEqualTo(1);
        assertThat(task.getErrorMessage()).isNull();

        // 步骤 4 (When)：连续失败模拟重试达到上限 3
        task.fail("再次失败");
        task.markForRetry(); // retryCount = 2
        task.fail("三次失败");
        task.markForRetry(); // retryCount = 3
        task.fail("四次失败");

        // 步骤 5 (Then)：断言达到最大允许上限 3 次，无法再重试并抛出状态异常
        assertThat(task.getRetryCount()).isEqualTo(3);
        assertThat(task.canRetry()).isFalse();
        assertThatThrownBy(task::markForRetry)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("任务不可重试");
    }
}
