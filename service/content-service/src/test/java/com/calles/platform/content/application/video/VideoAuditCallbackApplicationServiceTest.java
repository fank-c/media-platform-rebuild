package com.calles.platform.content.application.video;

import com.calles.platform.content.application.task.VideoTaskCoordinator;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.video.PublishStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VideoAuditCallbackApplicationService 异步审核结果回调应用服务单元测试。
 * <p>
 * 验证外部审核系统（如内容风控中台或审核服务）异步回调后与流水线任务协调器、发布门禁的联动与幂等防护机制。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoAuditCallbackApplicationService 审核回调服务测试")
class VideoAuditCallbackApplicationServiceTest {

    /**
     * 模拟视频聚合根持久化仓储。
     */
    @Mock
    private VideoContentRepository videoContentRepository;

    /**
     * 模拟流水线任务协调器。
     */
    @Mock
    private VideoTaskCoordinator videoTaskCoordinator;

    /**
     * 被测审核回调应用服务。
     */
    @InjectMocks
    private VideoAuditCallbackApplicationService auditCallbackService;

    /**
     * 测试审核通过回调流程：联动任务协调器标记 AUDIT 任务为 SUCCESS 并驱动门禁。
     */
    @Test
    @DisplayName("审核通过回调：联动任务协调器标记 AUDIT 任务为 SUCCESS 并驱动门禁")
    void shouldHandleAuditApproved() {
        // 步骤 1: 模拟已进入 AUDITING 审核中的视频聚合根
        VideoContent video = VideoContent.createDraft(
                "v_100", "cv10086", "author_1", "标题", "简介", "fv", "fc", 120, "tag"
        );
        video.submitForAudit();

        when(videoContentRepository.findById("v_100")).thenReturn(Optional.of(video));

        VideoRequests.AuditCallback request = new VideoRequests.AuditCallback("v_100", true, null);

        // 步骤 2: 处理审核通过回调
        auditCallbackService.handleAuditCallback(request);

        // 步骤 3: 验证任务协调器触发 AUDIT 任务完成
        verify(videoTaskCoordinator).completeTask("v_100", TaskType.AUDIT);
    }

    /**
     * 测试审核拒绝回调流程：联动任务协调器标记 AUDIT 任务为 FAILED 并触发流水线熔断。
     */
    @Test
    @DisplayName("审核拒绝回调：联动任务协调器标记 AUDIT 任务为 FAILED 并触发熔断")
    void shouldHandleAuditRejected() {
        // 步骤 1: 模拟已进入 AUDITING 审核中的视频聚合根
        VideoContent video = VideoContent.createDraft(
                "v_100", "cv10086", "author_1", "标题", "简介", "fv", "fc", 120, "tag"
        );
        video.submitForAudit();

        when(videoContentRepository.findById("v_100")).thenReturn(Optional.of(video));

        VideoRequests.AuditCallback request = new VideoRequests.AuditCallback("v_100", false, "封面图涉嫌低俗违规");

        // 步骤 2: 处理审核拒绝回调
        auditCallbackService.handleAuditCallback(request);

        // 步骤 3: 验证任务协调器触发 AUDIT 任务失败与驳回原因沉淀
        verify(videoTaskCoordinator).failTask("v_100", TaskType.AUDIT, "封面图涉嫌低俗违规");
    }

    /**
     * 测试非 AUDITING 状态视频接收到重复或错乱回调时的幂等静默忽略机制。
     */
    @Test
    @DisplayName("非 AUDITING 状态的视频接收回调时幂等忽略，不重复处理")
    void shouldIgnoreCallbackWhenNotInAuditingState() {
        // 步骤 1: 模拟仍处于 DRAFT 草稿态的视频
        VideoContent video = VideoContent.createDraft(
                "v_100", "cv10086", "author_1", "标题", "简介", "fv", "fc", 120, "tag"
        );
        when(videoContentRepository.findById("v_100")).thenReturn(Optional.of(video));

        VideoRequests.AuditCallback request = new VideoRequests.AuditCallback("v_100", true, null);

        // 步骤 2: 触发回调调用
        auditCallbackService.handleAuditCallback(request);

        // 步骤 3: 验证状态保持不变且没有任何任务协调调用
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.DRAFT);
        verify(videoTaskCoordinator, never()).completeTask(any(), any());
        verify(videoTaskCoordinator, never()).failTask(any(), any(), any());
    }
}
