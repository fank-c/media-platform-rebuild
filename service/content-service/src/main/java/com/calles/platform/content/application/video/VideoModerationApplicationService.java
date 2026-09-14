package com.calles.platform.content.application.video;

import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.exception.ContentException;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxMapper;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频平台风控与治理管理应用服务。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：负责平台管理端针对违规、涉政、侵权或低俗视频的冻结封禁与违规解除；</li>
 *   <li><b>协作对象</b>：协同 {@link VideoContentRepository} 与 {@link ContentOutboxMapper}；</li>
 *   <li><b>全局联动</b>：封禁或解封后，通过事务性发件箱 (Outbox) 发布领域事件，驱动搜索服务撤回/恢复索引，驱动网关或 CDN 清理缓存。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoModerationApplicationService {

    /** 视频持久化仓储。 */
    private final VideoContentRepository videoContentRepository;

    /** 事务性发件箱 Mapper。 */
    private final ContentOutboxMapper contentOutboxMapper;

    /**
     * 管理后台违规封禁视频。
     *
     * @param adminId 操作封禁的管理员账户 ID
     * @param id 目标视频内部主键 ID
     * @param reason 违规封禁的业务原因（如涉政、涉黄、版权侵权）
     * @throws ContentException 当找不到目标视频时抛出 404 NOT_FOUND
     */
    @Transactional
    public void banVideo(String adminId, String id, String reason) {
        // 步骤 1：查询待处置的视频实体
        VideoContent video = videoContentRepository.findById(id)
                .orElseThrow(() -> new ContentException(HttpStatus.NOT_FOUND, "未找到指定视频内容: " + id));

        // 步骤 2：置位领域聚合根状态为 DISABLED 并保存封禁原因
        video.ban(reason);
        videoContentRepository.updateById(video);

        // 步骤 3：在本地事务中生成 content.video.banned 领域事件，写入发件箱以广播下游下线缓存与推荐位
        Instant now = Instant.now();
        ContentOutboxRecord outbox = new ContentOutboxRecord(
                UUID.randomUUID().toString().replace("-", ""),
                video.getId(),
                "content.video.banned",
                1,
                String.format("{\"videoId\":\"%s\",\"vid\":\"%s\",\"adminId\":\"%s\",\"reason\":\"%s\"}",
                        video.getId(), video.getVid(), adminId, reason != null ? reason : ""),
                null,
                now
        );
        contentOutboxMapper.insert(outbox, Timestamp.from(now), "PENDING", Timestamp.from(now));

        log.warn("管理员 [{}] 封禁了视频 [{}], 原因: {}", adminId, id, reason);
    }

    /**
     * 管理后台解除视频封禁。
     *
     * @param adminId 操作解封的管理员账户 ID
     * @param id 目标视频内部主键 ID
     * @throws ContentException 当找不到目标视频时抛出 404 NOT_FOUND
     */
    @Transactional
    public void unbanVideo(String adminId, String id) {
        // 步骤 1：查询被封禁的视频实体
        VideoContent video = videoContentRepository.findById(id)
                .orElseThrow(() -> new ContentException(HttpStatus.NOT_FOUND, "未找到指定视频内容: " + id));

        // 步骤 2：恢复聚合根平台基准状态为 ACTIVE
        video.unban();
        videoContentRepository.updateById(video);

        // 步骤 3：在本地事务中写入 content.video.unbanned 领域事件以同步下游恢复各渠道索引
        Instant now = Instant.now();
        ContentOutboxRecord outbox = new ContentOutboxRecord(
                UUID.randomUUID().toString().replace("-", ""),
                video.getId(),
                "content.video.unbanned",
                1,
                String.format("{\"videoId\":\"%s\",\"vid\":\"%s\",\"adminId\":\"%s\"}",
                        video.getId(), video.getVid(), adminId),
                null,
                now
        );
        contentOutboxMapper.insert(outbox, Timestamp.from(now), "PENDING", Timestamp.from(now));

        log.info("管理员 [{}] 解封了视频 [{}]", adminId, id);
    }
}
