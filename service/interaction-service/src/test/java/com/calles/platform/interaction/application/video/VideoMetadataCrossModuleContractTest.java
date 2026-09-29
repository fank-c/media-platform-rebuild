package com.calles.platform.interaction.application.video;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

import com.calles.platform.interaction.application.video.VideoMetadataApplicationService.SnapshotApplyResult;
import com.calles.platform.interaction.domain.model.video.VideoSnapshot;
import com.calles.platform.interaction.domain.repository.VideoSnapshotRepository;
import com.calles.platform.interaction.interfaces.messaging.consumer.VideoMetadataConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 跨模块端到端契约集成测试：验证 content-service 生成的元数据事件能被 interaction-service 正确消费并写入快照。
 *
 * <p>测试目标：
 * <ol>
 *   <li>验证生产端输出的标准 JSON 载荷（包含 eventId 与 traceId）可被消费端完全兼容解析；</li>
 *   <li>验证消费后能建立 interaction_video_snapshot 本地时长快照；</li>
 *   <li>验证相同 eventId 重复投递时的幂等去重行为；</li>
 *   <li>验证即使面对未包装 eventId 的历史消息，消费端也能通过 AMQP MessageId 成功补全并落库。</li>
 * </ol>
 * </p>
 */
@DisplayName("跨模块视频元数据事件契约集成测试")
class VideoMetadataCrossModuleContractTest {
    private static final Instant TEST_INSTANT = Instant.parse("2026-09-27T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime TEST_TIME = LocalDateTime.ofInstant(TEST_INSTANT, ZoneOffset.UTC);


    private InMemorySnapshotRepository snapshotRepository;
    private VideoMetadataApplicationService applicationService;
    private VideoMetadataConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        snapshotRepository = new InMemorySnapshotRepository();
        applicationService = new VideoMetadataApplicationService(snapshotRepository, TEST_CLOCK);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        consumer = new VideoMetadataConsumer(applicationService, objectMapper);
    }

    @Test
    @DisplayName("content-service 生产的标准元数据事件被消费后成功建立本地时长快照")
    void shouldCreateSnapshotFromProducerGeneratedPayload() {
        // 步骤 1 (Given)：模拟 PublishGatekeeper 真实生成的 JSON 格式载荷
        String producerJson = """
                {
                    "eventId": "e9a0f443b74943f2a89f921d23456789",
                    "eventType": "content.video.metadata",
                    "eventVersion": 1,
                    "traceId": "trace_ctx_20260927_001",
                    "occurredAt": "2026-09-27T10:15:30Z",
                    "videoId": "v_internal_8888",
                    "vid": "cv_public_8888",
                    "duration": 420,
                    "metadataVersion": 1,
                    "status": "PUBLISHED",
                    "updatedAt": "2026-09-27T10:15:30Z"
                }
                """;

        // 步骤 2 (When)：模拟 RabbitMQ 监听器接收并触发消费
        consumer.onVideoMetadata(producerJson, null, null);

        // 步骤 3 (Then)：断言本地快照被成功建立且数据与生产端完全一致
        Optional<VideoSnapshot> snapshotOpt = snapshotRepository.findByVid("cv_public_8888");
        assertThat(snapshotOpt).isPresent();

        VideoSnapshot snapshot = snapshotOpt.get();
        assertThat(snapshot.getVid()).isEqualTo("cv_public_8888");
        assertThat(snapshot.getDuration()).isEqualTo(420);
        assertThat(snapshot.getMetadataVersion()).isEqualTo(1);
        assertThat(snapshot.getSourceEventId()).isEqualTo("e9a0f443b74943f2a89f921d23456789");
        assertThat(snapshot.getStatus()).isEqualTo("PUBLISHED");

        // 步骤 4 (And)：重复发送同一事件，验证幂等跳过，不产生二次写入
        consumer.onVideoMetadata(producerJson, null, null);
        assertThat(snapshotRepository.getWriteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("旧版未携带 eventId 的裸载荷在 AMQP MessageId 兜底后仍能成功建立快照")
    void shouldCreateSnapshotFromLegacyPayloadWithAmqpHeaderFallback() {
        // 步骤 1 (Given)：旧版裸载荷（仅包含业务字段）
        String legacyJson = """
                {
                    "videoId": "v_legacy_9999",
                    "vid": "cv_legacy_9999",
                    "duration": 600,
                    "metadataVersion": 1,
                    "status": "PUBLISHED",
                    "updatedAt": "2026-09-27T10:20:00Z"
                }
                """;

        // 步骤 2 (When)：模拟投递，AMQP 消息头携带 messageId
        consumer.onVideoMetadata(legacyJson, "e111222333444555666777888999000a", "trace_header_fallback");

        // 步骤 3 (Then)：断言快照建立成功且 source_event_id 准确记录为 AMQP messageId
        Optional<VideoSnapshot> snapshotOpt = snapshotRepository.findByVid("cv_legacy_9999");
        assertThat(snapshotOpt).isPresent();

        VideoSnapshot snapshot = snapshotOpt.get();
        assertThat(snapshot.getVid()).isEqualTo("cv_legacy_9999");
        assertThat(snapshot.getDuration()).isEqualTo(600);
        assertThat(snapshot.getSourceEventId()).isEqualTo("e111222333444555666777888999000a");
    }

    /**
     * 内存快照仓储实现，模拟实际数据库的唯一约束与版本控制。
     */
    private static final class InMemorySnapshotRepository implements VideoSnapshotRepository {

        private final Map<String, VideoSnapshot> store = new HashMap<>();
        private final Set<String> processedEvents = new LinkedHashSet<>();
        private int writeCount = 0;

        @Override
        public Optional<VideoSnapshot> findByVid(String vid) {
            return Optional.ofNullable(store.get(vid));
        }

        @Override
        public boolean existsBySourceEventId(String sourceEventId) {
            return processedEvents.contains(sourceEventId);
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

        public int getWriteCount() {
            return writeCount;
        }
    }
}
