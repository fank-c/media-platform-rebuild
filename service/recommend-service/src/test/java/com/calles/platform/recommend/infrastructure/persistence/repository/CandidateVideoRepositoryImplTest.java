package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.infrastructure.persistence.entity.CandidateVideoPO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.CandidateVideoMapper;
import java.time.LocalDateTime;
import java.util.Optional;
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
 * CandidateVideoRepositoryImpl 推荐候选仓储持久化测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CandidateVideoRepositoryImpl 仓储实现测试")
class CandidateVideoRepositoryImplTest {

    @Mock
    private CandidateVideoMapper candidateVideoMapper;

    @InjectMocks
    private CandidateVideoRepositoryImpl repository;

    @Test
    @DisplayName("insert：正常持久化调用 insertIgnore")
    void shouldInsertCandidate() {
        CandidateVideo candidate = CandidateVideo.createPublished(
                "c_001", "vid_100", "cv_abc", "author_1", "tag1", "top1", LocalDateTime.now()
        );
        when(candidateVideoMapper.insertIgnore(any())).thenReturn(1);

        int rows = repository.insert(candidate);
        assertThat(rows).isEqualTo(1);
        verify(candidateVideoMapper).insertIgnore(any());
    }

    @Test
    @DisplayName("findByVideoId：成功查询并转换为领域模型")
    void shouldFindByVideoId() {
        CandidateVideoPO po = CandidateVideoPO.builder()
                .id("c_001")
                .videoId("vid_100")
                .vid("cv_abc")
                .authorId("author_1")
                .status("ACTIVE")
                .publishedAt(LocalDateTime.now())
                .build();
        when(candidateVideoMapper.selectByVideoId("vid_100")).thenReturn(po);

        Optional<CandidateVideo> found = repository.findByVideoId("vid_100");
        assertThat(found).isPresent();
        assertThat(found.get().getVideoId()).isEqualTo("vid_100");
        assertThat(found.get().getVid()).isEqualTo("cv_abc");
        assertThat(found.get().getStatus()).isEqualTo(CandidateStatus.ACTIVE);
    }

    @Test
    @DisplayName("findByVid：成功根据公开短码查询")
    void shouldFindByVid() {
        CandidateVideoPO po = CandidateVideoPO.builder()
                .id("c_001")
                .videoId("vid_100")
                .vid("cv_abc")
                .authorId("author_1")
                .status("ACTIVE")
                .publishedAt(LocalDateTime.now())
                .build();
        when(candidateVideoMapper.selectByVid("cv_abc")).thenReturn(po);

        Optional<CandidateVideo> found = repository.findByVid("cv_abc");
        assertThat(found).isPresent();
        assertThat(found.get().getVid()).isEqualTo("cv_abc");
    }

    @Test
    @DisplayName("updateStatusByVideoId：原子调用状态更新")
    void shouldUpdateStatusByVideoId() {
        when(candidateVideoMapper.updateStatusByVideoId(eq("vid_100"), eq("OFFLINE"))).thenReturn(1);

        int rows = repository.updateStatusByVideoId("vid_100", CandidateStatus.OFFLINE);
        assertThat(rows).isEqualTo(1);
        verify(candidateVideoMapper).updateStatusByVideoId("vid_100", "OFFLINE");
    }
}
