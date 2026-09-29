package com.calles.platform.interaction.application.video;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

import com.calles.platform.interaction.application.video.VideoMetadataApplicationService.SnapshotApplyResult;
import com.calles.platform.interaction.domain.model.video.VideoSnapshot;
import com.calles.platform.interaction.domain.repository.VideoSnapshotRepository;
import com.calles.platform.interaction.interfaces.messaging.event.VideoMetadataMessage;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 视频元数据消费应用服务单元测试。
 *
 * <p>验证消费幂等、版本乱序保护与非法时长拒绝三类关键行为，对应方案中"重复消费""乱序消费"验证项。</p>
 */
class VideoMetadataApplicationServiceTest {
    private static final Instant TEST_INSTANT = Instant.parse("2026-09-27T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime TEST_TIME = LocalDateTime.ofInstant(TEST_INSTANT, ZoneOffset.UTC);


    private FakeSnapshotRepository repository;
    private VideoMetadataApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new FakeSnapshotRepository();
        service = new VideoMetadataApplicationService(repository, TEST_CLOCK);
    }

    @Test
    @DisplayName("合法元数据事件写入本地时长快照")
    void shouldApplyValidMetadata() {
        SnapshotApplyResult result = service.apply(message("evt_1", "cv_1", 300, 1));

        assertThat(result).isEqualTo(SnapshotApplyResult.APPLIED);
        assertThat(repository.findByVid("cv_1")).isPresent()
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.getDuration()).isEqualTo(300);
                    assertThat(snapshot.getMetadataVersion()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("同一来源事件重复投递只写入一次，第二次幂等跳过")
    void shouldSkipDuplicatedEvent() {
        assertThat(service.apply(message("evt_1", "cv_1", 300, 1))).isEqualTo(SnapshotApplyResult.APPLIED);
        assertThat(service.apply(message("evt_1", "cv_1", 300, 1))).isEqualTo(SnapshotApplyResult.SKIPPED_DUPLICATE);
        assertThat(repository.writeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("低版本元数据事件乱序到达时不覆盖新快照")
    void shouldNotOverwriteWithStaleVersion() {
        assertThat(service.apply(message("evt_2", "cv_1", 300, 2))).isEqualTo(SnapshotApplyResult.APPLIED);
        assertThat(service.apply(message("evt_1", "cv_1", 120, 1)))
                .isEqualTo(SnapshotApplyResult.SKIPPED_STALE_VERSION);
        assertThat(repository.findByVid("cv_1")).isPresent()
                .get()
                .extracting(VideoSnapshot::getDuration)
                .isEqualTo(300);
    }

    @Test
    @DisplayName("缺少视频短码或事件 ID 的载荷被拒绝")
    void shouldRejectMissingIdentifiers() {
        assertThat(service.apply(message(null, "cv_1", 300, 1))).isEqualTo(SnapshotApplyResult.REJECTED_INVALID);
        assertThat(service.apply(message("evt_1", " ", 300, 1))).isEqualTo(SnapshotApplyResult.REJECTED_INVALID);
        assertThat(service.apply(null)).isEqualTo(SnapshotApplyResult.REJECTED_INVALID);
        assertThat(repository.writeCount()).isZero();
    }

    @Test
    @DisplayName("时长非法时拒绝写入，避免 0 秒时长放宽防刷门槛")
    void shouldRejectInvalidDuration() {
        assertThat(service.apply(message("evt_1", "cv_1", 0, 1))).isEqualTo(SnapshotApplyResult.REJECTED_INVALID);
        assertThat(service.apply(message("evt_2", "cv_1", -5, 1))).isEqualTo(SnapshotApplyResult.REJECTED_INVALID);
        assertThat(repository.findByVid("cv_1")).isEmpty();
    }

    /**
     * 构造测试用元数据消息。
     *
     * @param eventId 事件 ID
     * @param vid 视频短码
     * @param duration 视频时长 (秒)
     * @param metadataVersion 元数据版本
     * @return 元数据消息
     */
    private VideoMetadataMessage message(String eventId, String vid, Integer duration, Integer metadataVersion) {
        return new VideoMetadataMessage(
                eventId,
                "content.video.metadata",
                1,
                "trace_1",
                Instant.now(),
                "video_internal_1",
                vid,
                duration,
                metadataVersion,
                "PUBLISHED",
                Instant.now());
    }

    /**
     * 内存视频元数据快照仓储，按主键 + 版本规则模拟真实写入语义。
     */
    private static final class FakeSnapshotRepository implements VideoSnapshotRepository {

        private final Map<String, VideoSnapshot> store = new HashMap<>();

        private final Set<String> processedEvents = new LinkedHashSet<>();

        private int writeCount;

        /**
         * 返回实际写入次数。
         *
         * @return 写入次数
         */
        private int writeCount() {
            return writeCount;
        }

        @Override
        public Optional<VideoSnapshot> findByVid(String vid) {
            return Optional.ofNullable(store.get(vid));
        }

        @Override
        public boolean existsBySourceEventId(String sourceEventId) {
            return sourceEventId != null && processedEvents.contains(sourceEventId);
        }

        @Override
        public boolean saveIfNewerOrSameVersion(VideoSnapshot snapshot) {
            VideoSnapshot existing = store.get(snapshot.getVid());
            if (existing != null && snapshot.getMetadataVersion() < existing.getMetadataVersion()) {
                return false;
            }
            store.put(snapshot.getVid(), snapshot);
            processedEvents.add(snapshot.getSourceEventId());
            writeCount++;
            return true;
        }
    }
}
