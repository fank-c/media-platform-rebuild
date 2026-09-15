package com.calles.platform.content.application.video;

import com.calles.platform.content.application.task.VideoTaskCoordinator;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.video.PublishStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.exception.ContentException;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核结果异步回调处理应用服务。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：接收审核微服务 (audit-service) 判定结果并驱动流水线状态流转；</li>
 *   <li><b>协作对象</b>：协同 {@link VideoContentRepository} 校验视频状态，协同 {@link VideoTaskCoordinator} 完成子任务并触发门禁判定；</li>
 *   <li><b>分布式一致性保障</b>：利用本地数据库事务更新任务状态并联动门禁决策器，
 *       审核通过驱动分级门禁，审核打回立即熔断取消全流水线并发布 content.video.rejected 领域事件；具备幂等防重复消费机制。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoAuditCallbackApplicationService {

    /** 视频聚合根持久化仓储。 */
    private final VideoContentRepository videoContentRepository;

    /** 视频流水线任务协调器。 */
    private final VideoTaskCoordinator videoTaskCoordinator;

    /**
     * 处理审核微服务发回的审核结果通知（具备幂等防护与事务性事件投递）。
     *
     * @param request 审核回调参数传输对象（包含视频 ID、判定结果与打回原因）
     * @throws ContentException 当视频 ID 找不到对应实体时抛出 404 NOT_FOUND
     */
    @Transactional
    public void handleAuditCallback(VideoRequests.AuditCallback request) {
        // 步骤 1：查询待处理的视频聚合根，不存在则抛出业务异常
        VideoContent video = videoContentRepository.findById(request.videoId())
                .orElseThrow(() -> new ContentException(HttpStatus.NOT_FOUND, "未找到审核回调对应的视频记录: " + request.videoId()));

        // 步骤 2：幂等安全防护：若视频已被提前处理或脱离 AUDITING 审核中状态，则安全忽略重复回调
        if (video.getPublishStatus() != PublishStatus.AUDITING) {
            log.info("视频 [{}] 当前生命周期为 [{}]，忽略重复的审核回调", video.getId(), video.getPublishStatus());
            return;
        }

        // 步骤 3：根据审核判定结果驱动子任务状态流转与门禁判定
        if (Boolean.TRUE.equals(request.passed())) {
            // 步骤 3A：机审/人审通过，标记 AUDIT 任务为 SUCCESS，顺带触发分级就绪发布门禁决策
            videoTaskCoordinator.completeTask(request.videoId(), TaskType.AUDIT);
            log.info("视频 [{}] 审核通过回调处理完成，已联动流水线驱动发布门禁", request.videoId());
        } else {
            // 步骤 3B：审核未通过，标记 AUDIT 任务为 FAILED，触发流水线熔断（聚合根 REJECTED、取消其余子任务、写入 rejected 事件）
            String reason = request.rejectReason() != null && !request.rejectReason().isBlank()
                    ? request.rejectReason() : "内容机审/人审未通过";
            videoTaskCoordinator.failTask(request.videoId(), TaskType.AUDIT, reason);
            log.info("视频 [{}] 审核未通过打回，原因: {}", request.videoId(), reason);
        }
    }
}
