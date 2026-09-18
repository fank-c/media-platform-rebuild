package com.calles.platform.content.application.task;

import com.calles.platform.content.application.outbox.ContentOutboxDispatchNotifier;
import com.calles.platform.content.domain.model.task.TaskStatus;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.task.VideoTask;
import com.calles.platform.content.domain.model.video.PublishStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.domain.repository.VideoTaskRepository;
import com.calles.platform.content.infrastructure.outbox.model.ContentOutboxRecord;
import com.calles.platform.content.infrastructure.outbox.persistence.ContentOutboxMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
 * 视频分级就绪发布门禁决策器 {@link PublishGatekeeper} 单元测试。
 *
 * <p>测试 AUDIT 审核、720P/1080P 转码基准流、VECTOR_EMBEDDING 向量特征计算等门禁组合判定、
 * 满足门禁时的原子发布与 Outbox 投递，以及审核驳回时级联取消子任务的边界行为。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublishGatekeeper 发布门禁测试")
class PublishGatekeeperTest {

    /** 模拟视频聚合根仓储。 */
    @Mock
    private VideoContentRepository videoContentRepository;

    /** 模拟流水线子任务仓储。 */
    @Mock
    private VideoTaskRepository videoTaskRepository;

    /** 模拟事务性 Outbox 事件持久化 Mapper。 */
    @Mock
    private ContentOutboxMapper contentOutboxMapper;

    /** 模拟发件箱提交后快速通知器。 */
    @Mock
    private ContentOutboxDispatchNotifier contentOutboxDispatchNotifier;

    /** 待测试的门禁决策器。 */
    @InjectMocks
    private PublishGatekeeper gatekeeper;

    /** 测试基准视频实体（预置为 AUDITING 审核中状态）。 */
    private VideoContent video;

    /**
     * 每个测试用例的前置准备：构建并提审基准视频实体。
     */
    @BeforeEach
    void setUp() {
        video = VideoContent.createDraft(
                "v_100", "cv10086", "author_1", "标题", "简介", "fv_1", "fc_1", 120, "tag"
        );
        video.submitForAudit();
    }

    /**
     * 测试当视频生命周期非 AUDITING（例如仍在 DRAFT 草稿）时，拒绝触发门禁评估。
     */
    @Test
    @DisplayName("非 AUDITING 状态的视频不可触发发布门禁")
    void shouldNotPublishWhenNotAuditing() {
        // 步骤 1 (Given)：创建处于草稿态未提审的视频
        VideoContent draftVideo = VideoContent.createDraft(
                "v_100", "cv10086", "author_1", "标题", "简介", "fv_1", "fc_1", 120, "tag"
        );

        // 步骤 2 (When)：调用门禁资格检查
        boolean can = gatekeeper.canPublish(draftVideo, List.of());

        // 步骤 3 (Then)：断言不满足发布门禁
        assertThat(can).isFalse();
    }

    /**
     * 测试无独立流水线子任务时，降级兼容允许直接发布上线。
     */
    @Test
    @DisplayName("无独立流水线子任务时，降级允许直接通过发布")
    void shouldAllowPublishWhenTasksEmpty() {
        // 步骤 1 (When)：传入空任务列表
        boolean can = gatekeeper.canPublish(video, List.of());

        // 步骤 2 (Then)：断言降级放行
        assertThat(can).isTrue();
    }

    /**
     * 测试基准门禁达成：AUDIT 通过 + 720P 基准流完成 + 向量计算完成，即使 4K 仍未完成也准予发布。
     */
    @Test
    @DisplayName("审核通过 + 720P基准流完成 + 向量计算完成 -> 满足分级就绪门禁")
    void shouldPublishWhen720pAndVectorAndAuditReady() {
        // 步骤 1 (Given)：构建审核通过、720P转码完成、向量计算完成，而 4K 仍在排队的任务集
        VideoTask auditTask = VideoTask.create("v_100", TaskType.AUDIT);
        auditTask.complete();

        VideoTask transcode720p = VideoTask.create("v_100", TaskType.TRANSCODE_720P);
        transcode720p.complete();

        VideoTask vectorTask = VideoTask.create("v_100", TaskType.VECTOR_EMBEDDING);
        vectorTask.complete();

        VideoTask transcode4k = VideoTask.create("v_100", TaskType.TRANSCODE_4K); // 4K 仍处于 PENDING

        List<VideoTask> tasks = List.of(auditTask, transcode720p, vectorTask, transcode4k);

        // 步骤 2 (When)：评估门禁资格
        boolean can = gatekeeper.canPublish(video, tasks);

        // 步骤 3 (Then)：断言分级就绪准入通过
        assertThat(can).isTrue();
    }

    /**
     * 测试基准门禁达成：AUDIT 通过 + 1080P 基准流完成 + 向量计算完成，准予发布。
     */
    @Test
    @DisplayName("审核通过 + 1080P基准流完成 + 向量计算完成 -> 满足分级就绪门禁")
    void shouldPublishWhen1080pAndVectorAndAuditReady() {
        // 步骤 1 (Given)：构建审核完成、1080P转码完成、向量计算完成任务集
        VideoTask auditTask = VideoTask.create("v_100", TaskType.AUDIT);
        auditTask.complete();

        VideoTask transcode1080p = VideoTask.create("v_100", TaskType.TRANSCODE_1080P);
        transcode1080p.complete();

        VideoTask vectorTask = VideoTask.create("v_100", TaskType.VECTOR_EMBEDDING);
        vectorTask.complete();

        List<VideoTask> tasks = List.of(auditTask, transcode1080p, vectorTask);

        // 步骤 2 (When)：评估门禁
        boolean can = gatekeeper.canPublish(video, tasks);

        // 步骤 3 (Then)：断言准予发布
        assertThat(can).isTrue();
    }

    /**
     * 测试仅 4K 完成但缺失 720P/1080P 基础画质时，门禁不予放行。
     */
    @Test
    @DisplayName("仅 4K 就绪但无 720P/1080P 基准流 -> 不满足门禁")
    void shouldNotPublishWhenOnly4kReadyWithoutBaseline() {
        // 步骤 1 (Given)：仅 4K 完成，缺少 720P/1080P 基准流
        VideoTask auditTask = VideoTask.create("v_100", TaskType.AUDIT);
        auditTask.complete();

        VideoTask transcode4k = VideoTask.create("v_100", TaskType.TRANSCODE_4K);
        transcode4k.complete();

        VideoTask vectorTask = VideoTask.create("v_100", TaskType.VECTOR_EMBEDDING);
        vectorTask.complete();

        List<VideoTask> tasks = List.of(auditTask, transcode4k, vectorTask);

        // 步骤 2 (When)：评估门禁
        boolean can = gatekeeper.canPublish(video, tasks);

        // 步骤 3 (Then)：断言未满足发布门禁
        assertThat(can).isFalse();
    }

    /**
     * 测试多模态向量计算尚未完成时，不可提前发布（保障发布后立即能被召回检索）。
     */
    @Test
    @DisplayName("向量计算未完成 -> 不满足门禁")
    void shouldNotPublishWhenVectorNotReady() {
        // 步骤 1 (Given)：向量计算任务仍在 RUNNING 中
        VideoTask auditTask = VideoTask.create("v_100", TaskType.AUDIT);
        auditTask.complete();

        VideoTask transcode720p = VideoTask.create("v_100", TaskType.TRANSCODE_720P);
        transcode720p.complete();

        VideoTask vectorTask = VideoTask.create("v_100", TaskType.VECTOR_EMBEDDING);
        vectorTask.start(); // RUNNING

        List<VideoTask> tasks = List.of(auditTask, transcode720p, vectorTask);

        // 步骤 2 (When)：评估门禁
        boolean can = gatekeeper.canPublish(video, tasks);

        // 步骤 3 (Then)：断言门禁阻断
        assertThat(can).isFalse();
    }

    /**
     * 测试 tryPublishIfEligible 满足门禁时，聚合根流转为 PUBLISHED 并原子持久化 Outbox 发布事件。
     */
    @Test
    @DisplayName("tryPublishIfEligible 满足门禁时流转为 PUBLISHED 并写 Outbox")
    void shouldTryPublishIfEligibleSuccessfully() {
        // 步骤 1 (Given)：模拟基准门禁均已达成
        VideoTask auditTask = VideoTask.create("v_100", TaskType.AUDIT);
        auditTask.complete();
        VideoTask transcode720p = VideoTask.create("v_100", TaskType.TRANSCODE_720P);
        transcode720p.complete();
        VideoTask vectorTask = VideoTask.create("v_100", TaskType.VECTOR_EMBEDDING);
        vectorTask.complete();

        when(videoContentRepository.findById("v_100")).thenReturn(Optional.of(video));
        when(videoTaskRepository.findByVideoId("v_100")).thenReturn(List.of(auditTask, transcode720p, vectorTask));
        when(videoContentRepository.updateById(video)).thenReturn(1);

        // 步骤 2 (When)：触发尝试发布
        boolean published = gatekeeper.tryPublishIfEligible("v_100");

        // 步骤 3 (Then)：断言发布成功，视频状态变更为 PUBLISHED，且持久化了 Outbox 发布记录
        assertThat(published).isTrue();
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.PUBLISHED);
        verify(contentOutboxMapper).insert(any(ContentOutboxRecord.class), any(Timestamp.class), eq("PENDING"), any(Timestamp.class));
    }

    /**
     * 测试审核打回时，视频流转为 REJECTED、级联批量取消其余进行中子任务并记录 Outbox 驳回事件。
     */
    @Test
    @DisplayName("rejectAndCancelPipeline 审核驳回流转为 REJECTED 且级联取消未完成子任务")
    void shouldRejectAndCancelPipeline() {
        // 步骤 1 (Given)：构建转码与向量提取均在 RUNNING 中的子任务
        VideoTask transcode720p = VideoTask.create("v_100", TaskType.TRANSCODE_720P);
        transcode720p.start();
        VideoTask vectorTask = VideoTask.create("v_100", TaskType.VECTOR_EMBEDDING);
        vectorTask.start();

        when(videoContentRepository.findById("v_100")).thenReturn(Optional.of(video));
        when(videoTaskRepository.findByVideoId("v_100")).thenReturn(List.of(transcode720p, vectorTask));

        // 步骤 2 (When)：触发审核驳回处理
        gatekeeper.rejectAndCancelPipeline("v_100", "视频涉嫌侵权");

        // 步骤 3 (Then)：断言视频状态跃迁为 REJECTED，子任务级联变更为 CANCELED 并写库与发件箱
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.REJECTED);
        assertThat(video.getRejectReason()).isEqualTo("视频涉嫌侵权");
        assertThat(transcode720p.getStatus()).isEqualTo(TaskStatus.CANCELED);
        assertThat(vectorTask.getStatus()).isEqualTo(TaskStatus.CANCELED);
        verify(videoTaskRepository).updateById(transcode720p);
        verify(videoTaskRepository).updateById(vectorTask);
        verify(contentOutboxMapper).insert(any(ContentOutboxRecord.class), any(Timestamp.class), eq("PENDING"), any(Timestamp.class));
    }
}
