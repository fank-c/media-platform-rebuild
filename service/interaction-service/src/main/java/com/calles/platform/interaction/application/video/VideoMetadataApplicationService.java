package com.calles.platform.interaction.application.video;

import com.calles.platform.interaction.domain.model.video.VideoSnapshot;
import com.calles.platform.interaction.domain.repository.VideoSnapshotRepository;
import com.calles.platform.interaction.interfaces.messaging.event.VideoMetadataMessage;
import java.time.Clock;
import com.calles.platform.interaction.application.InteractionTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频元数据消费应用服务。
 *
 * <p>职责边界：把 content-service 发布的视频元数据事件幂等转换为 interaction-service 自有的本地时长快照。
 * 本服务不修改任何观看状态，也不产生播放量或事件。</p>
 *
 * <p>幂等与顺序策略：
 * <ol>
 *   <li>按来源事件 ID 去重，重复投递直接跳过；</li>
 *   <li>非法时长 (小于等于 0) 不落库，避免用"0 秒时长"把播放量门槛退化成极低值；</li>
 *   <li>低版本元数据事件不覆盖已有快照，避免乱序消费导致时长回退。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoMetadataApplicationService {

    private final VideoSnapshotRepository videoSnapshotRepository;
    /** 仅当上游事件没有时间时提供服务端 UTC 接收时刻。 */
    private final Clock clock;

    /**
     * 消费结果。
     */
    public enum SnapshotApplyResult {
        /** 快照已成功写入或更新。 */
        APPLIED,
        /** 同一来源事件重复投递，已幂等跳过。 */
        SKIPPED_DUPLICATE,
        /** 元数据版本低于既有快照，按乱序保护跳过。 */
        SKIPPED_STALE_VERSION,
        /** 载荷非法（缺少 vid/eventId 或时长不可用），未写入。 */
        REJECTED_INVALID
    }

    /**
     * 处理一条视频元数据事件。
     *
     * @param message 视频元数据消息
     * @return 处理结果，调用方据此决定是否需要人工介入
     */
    @Transactional(rollbackFor = Exception.class)
    public SnapshotApplyResult apply(VideoMetadataMessage message) {
        if (message == null) {
            return SnapshotApplyResult.REJECTED_INVALID;
        }

        String vid = message.vid();
        String eventId = message.eventId();
        if (vid == null || vid.isBlank() || eventId == null || eventId.isBlank()) {
            log.warn("丢弃缺少 vid 或 eventId 的视频元数据事件: payload={}", message);
            return SnapshotApplyResult.REJECTED_INVALID;
        }

        // 步骤 1：按来源事件 ID 幂等去重，重复投递不产生任何写入
        if (videoSnapshotRepository.existsBySourceEventId(eventId)) {
            log.debug("视频元数据事件已消费过，幂等跳过: eventId={}, vid={}", eventId, vid);
            return SnapshotApplyResult.SKIPPED_DUPLICATE;
        }

        // 步骤 2：拒绝非法时长，避免有效快照被 0 秒时长污染而放宽防刷门槛
        int duration = message.duration() != null ? message.duration() : 0;
        if (duration <= 0) {
            log.warn("视频元数据时长非法，拒绝写入快照: eventId={}, vid={}, duration={}", eventId, vid, duration);
            return SnapshotApplyResult.REJECTED_INVALID;
        }

        // 步骤 3：按"同版本或更高版本"规则写入本地快照
        VideoSnapshot snapshot = VideoSnapshot.create(
                vid.trim(),
                duration,
                message.metadataVersion() != null ? message.metadataVersion() : 1,
                eventId,
                message.status(),
                toLocalDateTime(message.updatedAt() != null ? message.updatedAt() : message.occurredAt(), clock)
        );

        boolean written = videoSnapshotRepository.saveIfNewerOrSameVersion(snapshot);
        if (!written) {
            return SnapshotApplyResult.SKIPPED_STALE_VERSION;
        }

        log.info("视频元数据快照已更新: vid={}, duration={}, metadataVersion={}, sourceEventId={}",
                snapshot.getVid(), snapshot.getDuration(), snapshot.getMetadataVersion(), eventId);
        return SnapshotApplyResult.APPLIED;
    }

    /**
     * 将事件时间按 UTC 转换，事件缺失时间时使用本次接收时刻。
     *
     * @param instant 事件时间，允许为空
     * @param clock 服务端 UTC 时钟
     * @return UTC 语义的本地时间
     */
    private LocalDateTime toLocalDateTime(Instant instant, Clock clock) {
        if (instant == null) {
            return InteractionTime.utcNow(clock);
        }
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
