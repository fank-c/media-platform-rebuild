package com.calles.platform.interaction.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.video.VideoSnapshot;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoSnapshotPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoSnapshotMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 视频元数据快照仓储实现类单元测试。
 *
 * <p>测试覆盖：新记录插入、原子条件更新成功、低版本内存快速拦截、并发插入冲突自愈重试、
 * 以及并发 Check-Then-Act 场景下原子更新被更高版本拒绝防降级等分支。</p>
 */
@ExtendWith(MockitoExtension.class)
class VideoSnapshotRepositoryImplTest {

    private static final String VID = "cv_test_001";

    @Mock
    private VideoSnapshotMapper mapper;

    private VideoSnapshotRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new VideoSnapshotRepositoryImpl(mapper);
    }

    @Test
    @DisplayName("首次遇到视频快照时成功执行插入")
    void shouldInsertWhenNotExisting() {
        when(mapper.selectById(VID)).thenReturn(null);
        when(mapper.insertSnapshot(any(VideoSnapshotPO.class))).thenReturn(1);

        VideoSnapshot snapshot = VideoSnapshot.create(VID, 100, 1, "evt_001", "PUBLISHED", LocalDateTime.now());
        boolean success = repository.saveIfNewerOrSameVersion(snapshot);

        assertThat(success).isTrue();
        verify(mapper).insertSnapshot(any(VideoSnapshotPO.class));
        verify(mapper, never()).updateIfNewerOrSameVersion(any(VideoSnapshotPO.class));
    }

    @Test
    @DisplayName("并发插入冲突(DuplicateKeyException)时自愈执行原子条件更新")
    void shouldRecoverFromDuplicateKeyAndPerformAtomicUpdate() {
        when(mapper.selectById(VID)).thenReturn(null);
        when(mapper.insertSnapshot(any(VideoSnapshotPO.class))).thenThrow(new DuplicateKeyException("uk conflict"));
        when(mapper.updateIfNewerOrSameVersion(any(VideoSnapshotPO.class))).thenReturn(1);

        VideoSnapshot snapshot = VideoSnapshot.create(VID, 100, 1, "evt_001", "PUBLISHED", LocalDateTime.now());
        boolean success = repository.saveIfNewerOrSameVersion(snapshot);

        assertThat(success).isTrue();
        verify(mapper).insertSnapshot(any(VideoSnapshotPO.class));
        verify(mapper).updateIfNewerOrSameVersion(any(VideoSnapshotPO.class));
    }

    @Test
    @DisplayName("已有记录且当前入参版本更高时，原子条件更新成功")
    void shouldUpdateWhenVersionIsNewer() {
        VideoSnapshotPO existing = new VideoSnapshotPO();
        existing.setVid(VID);
        existing.setMetadataVersion(1);

        when(mapper.selectById(VID)).thenReturn(existing);
        when(mapper.updateIfNewerOrSameVersion(any(VideoSnapshotPO.class))).thenReturn(1);

        VideoSnapshot snapshot = VideoSnapshot.create(VID, 200, 2, "evt_002", "PUBLISHED", LocalDateTime.now());
        boolean success = repository.saveIfNewerOrSameVersion(snapshot);

        assertThat(success).isTrue();
        verify(mapper).updateIfNewerOrSameVersion(any(VideoSnapshotPO.class));
        verify(mapper, never()).insertSnapshot(any(VideoSnapshotPO.class));
    }

    @Test
    @DisplayName("入参版本低于数据库当前版本时，内存前置拦截，不调用更新 SQL")
    void shouldRejectWhenVersionIsOlder() {
        VideoSnapshotPO existing = new VideoSnapshotPO();
        existing.setVid(VID);
        existing.setMetadataVersion(3);

        when(mapper.selectById(VID)).thenReturn(existing);

        VideoSnapshot snapshot = VideoSnapshot.create(VID, 150, 2, "evt_002", "PUBLISHED", LocalDateTime.now());
        boolean success = repository.saveIfNewerOrSameVersion(snapshot);

        assertThat(success).isFalse();
        verify(mapper, never()).updateIfNewerOrSameVersion(any(VideoSnapshotPO.class));
        verify(mapper, never()).insertSnapshot(any(VideoSnapshotPO.class));
    }

    @Test
    @DisplayName("并发竞争场景：查询通过但在更新执行前被更高版本抢占，SQL 条件匹配 0 行，安全拒绝降级")
    void shouldReturnFalseWhenAtomicUpdateRejectedByHigherVersion() {
        // 模拟消费者读到了版本 1，但进入 update 时已有并发事务写入了版本 3
        VideoSnapshotPO existing = new VideoSnapshotPO();
        existing.setVid(VID);
        existing.setMetadataVersion(1);

        when(mapper.selectById(VID)).thenReturn(existing);
        // SQL WHERE metadata_version <= 2 匹配 0 行
        when(mapper.updateIfNewerOrSameVersion(any(VideoSnapshotPO.class))).thenReturn(0);

        VideoSnapshot snapshot = VideoSnapshot.create(VID, 180, 2, "evt_002", "PUBLISHED", LocalDateTime.now());
        boolean success = repository.saveIfNewerOrSameVersion(snapshot);

        assertThat(success).isFalse();
        verify(mapper).updateIfNewerOrSameVersion(any(VideoSnapshotPO.class));
    }

    @Test
    @DisplayName("findByVid 与 existsBySourceEventId 正常映射与查询")
    void shouldDelegateFindAndExists() {
        VideoSnapshotPO po = new VideoSnapshotPO();
        po.setVid(VID);
        po.setDuration(120);
        po.setMetadataVersion(1);
        po.setSourceEventId("evt_001");
        po.setStatus("PUBLISHED");

        when(mapper.selectById(VID)).thenReturn(po);
        when(mapper.countBySourceEventId("evt_001")).thenReturn(1);
        when(mapper.countBySourceEventId("evt_other")).thenReturn(0);

        Optional<VideoSnapshot> found = repository.findByVid(VID);
        assertThat(found).isPresent();
        assertThat(found.get().getVid()).isEqualTo(VID);
        assertThat(found.get().getDuration()).isEqualTo(120);

        assertThat(repository.existsBySourceEventId("evt_001")).isTrue();
        assertThat(repository.existsBySourceEventId("evt_other")).isFalse();
    }
}
