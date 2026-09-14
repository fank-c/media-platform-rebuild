package com.calles.platform.content.application.video;

import com.calles.platform.content.domain.model.video.PublishStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.exception.ContentException;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxMapper;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxRecord;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
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
 *   <li><b>所属边界</b>：接收审核微服务 (audit-service) 判定结果并执行内容生命周期状态跃迁；</li>
 *   <li><b>协作对象</b>：协同 {@link VideoContentRepository} 持久化视频状态，协同 {@link ContentOutboxMapper} 实现事务性事件写入；</li>
 *   <li><b>分布式一致性保障</b>：利用本地数据库事务同时更新视频状态与发件箱 (Transactional Outbox)，杜绝双写不一致；具备幂等防重复消费机制。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoAuditCallbackApplicationService {

    /** 视频聚合根持久化仓储。 */
    private final VideoContentRepository videoContentRepository;

    /** 事务性发件箱 (Outbox) Mapper。 */
    private final ContentOutboxMapper contentOutboxMapper;

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

        Instant now = Instant.now();
        // 步骤 3：根据审核判定结果执行状态跃迁与领域事件写入
        if (Boolean.TRUE.equals(request.passed())) {
            // 步骤 3A：机审/人审通过，聚合根跃迁为 PUBLISHED 并记录正式发布时间
            video.publish(LocalDateTime.now());
            // 步骤 3B：在同一事务中写入 content.video.published 发件箱记录，驱动下游转码与推荐搜索索引
            ContentOutboxRecord outbox = new ContentOutboxRecord(
                    UUID.randomUUID().toString().replace("-", ""),
                    video.getId(),
                    "content.video.published",
                    1,
                    String.format("{\"videoId\":\"%s\",\"vid\":\"%s\",\"authorId\":\"%s\",\"videoFileId\":\"%s\",\"coverFileId\":\"%s\",\"publishedAt\":\"%s\"}",
                            video.getId(), video.getVid(), video.getAuthorId(), video.getVideoFileId(), video.getCoverFileId(), video.getPublishedAt()),
                    null,
                    now
            );
            contentOutboxMapper.insert(outbox, Timestamp.from(now), "PENDING", Timestamp.from(now));
            log.info("视频 [{}] 审核通过并正式发布，记录 published 事件", video.getId());
        } else {
            // 步骤 3C：审核未通过，聚合根跃迁为 REJECTED 驳回状态并记录具体违规原因
            video.reject(request.rejectReason());
            // 步骤 3D：写入 content.video.rejected 发件箱记录，通知通知微服务或创作者工作台站内信
            ContentOutboxRecord outbox = new ContentOutboxRecord(
                    UUID.randomUUID().toString().replace("-", ""),
                    video.getId(),
                    "content.video.rejected",
                    1,
                    String.format("{\"videoId\":\"%s\",\"vid\":\"%s\",\"reason\":\"%s\"}",
                            video.getId(), video.getVid(), video.getRejectReason()),
                    null,
                    now
            );
            contentOutboxMapper.insert(outbox, Timestamp.from(now), "PENDING", Timestamp.from(now));
            log.info("视频 [{}] 审核未通过打回，原因: {}", video.getId(), video.getRejectReason());
        }

        // 步骤 4：持久化更新聚合根最新状态
        videoContentRepository.updateById(video);
    }
}
