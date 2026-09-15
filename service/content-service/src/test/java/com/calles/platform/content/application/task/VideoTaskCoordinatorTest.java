package com.calles.platform.content.application.task;

import com.calles.platform.content.domain.model.task.TaskStatus;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.task.VideoTask;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.domain.repository.VideoTaskRepository;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频异步发布流水线任务协调器 {@link VideoTaskCoordinator} 单元测试。
 *
 * <p>测试提审时批量初始化 5 个子任务、启动任务、进度更新、完成联动门禁触发、审核失败级联熔断以及全景进度组装。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoTaskCoordinator 任务协调器测试")
class VideoTaskCoordinatorTest {

    /** 模拟子任务仓储。 */
    @Mock
    private VideoTaskRepository videoTaskRepository;

    /** 模拟视频聚合根仓储。 */
    @Mock
    private VideoContentRepository videoContentRepository;

    /** 模拟发布门禁决策器。 */
    @Mock
    private PublishGatekeeper publishGatekeeper;

    /** 待测试的任务协调器。 */
    @InjectMocks
    private VideoTaskCoordinator coordinator;

    /** 测试基准视频实体。 */
    private VideoContent video;

    /**
     * 每个测试用例的前置准备：初始化提审中的视频。
     */
    @BeforeEach
    void setUp() {
        video = VideoContent.createDraft(
                "v_100", "cv10086", "author_1", "标题", "简介", "fv_1", "fc_1", 120, "tag"
        );
        video.submitForAudit();
    }

    /**
     * 测试提审触发初始化流水线任务，批量生成 AUDIT, 720P, 1080P, 4K, VECTOR_EMBEDDING 5 个任务。
     */
    @Test
    @DisplayName("initPipelineTasks 视频提审批量生成 5 个初始子任务")
    void shouldInitPipelineTasks() {
        // 步骤 1 (Given)：模拟此前无任务记录
        when(videoTaskRepository.findByVideoId("v_100")).thenReturn(Collections.emptyList());

        // 步骤 2 (When)：触发初始化任务网格
        coordinator.initPipelineTasks("v_100");

        // 步骤 3 (Then)：捕获 batchInsert 入参，验证包含 5 个指定类型的任务
        ArgumentCaptor<List<VideoTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(videoTaskRepository).batchInsert(captor.capture());

        List<VideoTask> captured = captor.getValue();
        assertThat(captured).hasSize(5);
        assertThat(captured).extracting(VideoTask::getTaskType)
                .containsExactlyInAnyOrder(
                        TaskType.AUDIT,
                        TaskType.TRANSCODE_720P,
                        TaskType.TRANSCODE_1080P,
                        TaskType.TRANSCODE_4K,
                        TaskType.VECTOR_EMBEDDING
                );
    }

    /**
     * 测试启动任务将指定子任务状态流转为 RUNNING 并回写持久层。
     */
    @Test
    @DisplayName("startTask 标记任务进入 RUNNING 状态")
    void shouldStartTask() {
        // 步骤 1 (Given)：模拟命中已存在的 720P 转码任务
        VideoTask task = VideoTask.create("v_100", TaskType.TRANSCODE_720P);
        when(videoTaskRepository.findByVideoIdAndTaskType("v_100", TaskType.TRANSCODE_720P))
                .thenReturn(Optional.of(task));

        // 步骤 2 (When)：执行启动任务
        coordinator.startTask("v_100", TaskType.TRANSCODE_720P);

        // 步骤 3 (Then)：断言状态变为 RUNNING 且更新了仓储
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
        verify(videoTaskRepository).updateById(task);
    }

    /**
     * 测试更新指定子任务进度百分比。
     */
    @Test
    @DisplayName("updateProgress 更新执行进度")
    void shouldUpdateProgress() {
        // 步骤 1 (Given)：模拟运行中的 1080P 转码任务
        VideoTask task = VideoTask.create("v_100", TaskType.TRANSCODE_1080P);
        task.start();
        when(videoTaskRepository.findByVideoIdAndTaskType("v_100", TaskType.TRANSCODE_1080P))
                .thenReturn(Optional.of(task));

        // 步骤 2 (When)：汇报 75% 进度
        coordinator.updateProgress("v_100", TaskType.TRANSCODE_1080P, 75);

        // 步骤 3 (Then)：断言任务进度更新为 75 并持久化
        assertThat(task.getProgress()).isEqualTo(75);
        verify(videoTaskRepository).updateById(task);
    }

    /**
     * 测试子任务完成标记为 SUCCESS，并主动触发门禁决策评估。
     */
    @Test
    @DisplayName("completeTask 标记任务完成并触发门禁决策")
    void shouldCompleteTaskAndTriggerGatekeeper() {
        // 步骤 1 (Given)：模拟运行中的审核任务
        VideoTask task = VideoTask.create("v_100", TaskType.AUDIT);
        task.start();
        when(videoTaskRepository.findByVideoIdAndTaskType("v_100", TaskType.AUDIT))
                .thenReturn(Optional.of(task));

        // 步骤 2 (When)：完成审核任务
        coordinator.completeTask("v_100", TaskType.AUDIT);

        // 步骤 3 (Then)：断言任务为 SUCCESS，且联动触发了发布门禁评估
        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
        verify(videoTaskRepository).updateById(task);
        verify(publishGatekeeper).tryPublishIfEligible("v_100");
    }

    /**
     * 测试 AUDIT 审核任务失败时，联动门禁熔断全流水线并打回视频。
     */
    @Test
    @DisplayName("failTask 针对 AUDIT 任务失败时联动门禁驳回全流水线")
    void shouldFailTaskAndRejectWhenAuditFailed() {
        // 步骤 1 (Given)：模拟运行中的审核任务
        VideoTask task = VideoTask.create("v_100", TaskType.AUDIT);
        task.start();
        when(videoTaskRepository.findByVideoIdAndTaskType("v_100", TaskType.AUDIT))
                .thenReturn(Optional.of(task));

        // 步骤 2 (When)：标记审核任务失败并给出原因
        coordinator.failTask("v_100", TaskType.AUDIT, "画面违规");

        // 步骤 3 (Then)：断言任务为 FAILED，且联动门禁 rejectAndCancelPipeline 终止全流水线
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getErrorMessage()).isEqualTo("画面违规");
        verify(videoTaskRepository).updateById(task);
        verify(publishGatekeeper).rejectAndCancelPipeline("v_100", "画面违规");
    }

    /**
     * 测试查询视频全量流水线进度，正确聚合各子任务明细与发布资格。
     */
    @Test
    @DisplayName("getPipelineProgress 组装流水线进度出参")
    void shouldGetPipelineProgress() {
        // 步骤 1 (Given)：构建已完成的审核任务并模拟仓储
        VideoTask task = VideoTask.create("v_100", TaskType.AUDIT);
        task.complete();

        when(videoContentRepository.findById("v_100")).thenReturn(Optional.of(video));
        when(videoTaskRepository.findByVideoId("v_100")).thenReturn(List.of(task));
        when(publishGatekeeper.canPublish(eq(video), any())).thenReturn(false);

        // 步骤 2 (When)：获取流水线聚合进度
        VideoResponses.PipelineProgress progress = coordinator.getPipelineProgress("v_100");

        // 步骤 3 (Then)：断言聚合 DTO 数据一致性
        assertThat(progress.videoId()).isEqualTo("v_100");
        assertThat(progress.publishStatus()).isEqualTo("AUDITING");
        assertThat(progress.eligibleForPublish()).isFalse();
        assertThat(progress.tasks()).hasSize(1);
        assertThat(progress.tasks().get(0).taskType()).isEqualTo("AUDIT");
        assertThat(progress.tasks().get(0).status()).isEqualTo("SUCCESS");
    }
}
