package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.CommonStatus;
import com.calles.platform.content.domain.model.video.ContentVisibility;
import com.calles.platform.content.domain.model.video.PublishStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.infrastructure.persistence.entity.VideoContentPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoContentMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoContentRepositoryImpl 仓储实现测试")
class VideoContentRepositoryImplTest {

    @Mock
    private VideoContentMapper mapper;

    @InjectMocks
    private VideoContentRepositoryImpl repository;

    private VideoContent sampleVideo;

    @BeforeEach
    void setUp() {
        sampleVideo = VideoContent.createDraft(
                "vid_123",
                "cv10086",
                "user_001",
                "微服务入门",
                "详细介绍",
                "file_vid_1",
                "file_cov_1",
                300,
                "架构,微服务"
        );
    }

    @Test
    @DisplayName("新增视频落库保存")
    void shouldInsertVideo() {
        when(mapper.insert(any(VideoContentPO.class))).thenReturn(1);

        int rows = repository.insert(sampleVideo);

        assertThat(rows).isEqualTo(1);
        ArgumentCaptor<VideoContentPO> captor = ArgumentCaptor.forClass(VideoContentPO.class);
        verify(mapper).insert(captor.capture());
        VideoContentPO captured = captor.getValue();
        assertThat(captured.getId()).isEqualTo("vid_123");
        assertThat(captured.getVid()).isEqualTo("cv10086");
        assertThat(captured.getStatus()).isEqualTo(CommonStatus.ACTIVE.getValue());
        assertThat(captured.getPublishStatus()).isEqualTo(PublishStatus.DRAFT.getValue());
        assertThat(captured.getRevision()).isZero();
    }

    @Test
    @DisplayName("按主键更新视频（带乐观锁）")
    void shouldUpdateVideoWithOptimisticLock() {
        when(mapper.updateWithOptimisticLock(any(VideoContentPO.class))).thenReturn(1);

        sampleVideo.updateMetadata("新标题", "新简介", null, null);
        int rows = repository.updateById(sampleVideo);

        assertThat(rows).isEqualTo(1);
        verify(mapper).updateWithOptimisticLock(any(VideoContentPO.class));
    }

    @Test
    @DisplayName("根据 ID 查询有效视频")
    void shouldFindById() {
        VideoContentPO po = VideoContentPO.fromDomain(sampleVideo);
        when(mapper.selectById("vid_123")).thenReturn(po);

        Optional<VideoContent> found = repository.findById("vid_123");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("vid_123");
        assertThat(found.get().getVid()).isEqualTo("cv10086");
        assertThat(found.get().getStatus()).isEqualTo(CommonStatus.ACTIVE);
        assertThat(found.get().getPublishStatus()).isEqualTo(PublishStatus.DRAFT);
        assertThat(found.get().getTitle()).isEqualTo("微服务入门");
    }

    @Test
    @DisplayName("根据业务 vid 查询视频")
    void shouldFindByVid() {
        VideoContentPO po = VideoContentPO.fromDomain(sampleVideo);
        when(mapper.selectByVid("cv10086")).thenReturn(po);

        Optional<VideoContent> found = repository.findByVid("cv10086");

        assertThat(found).isPresent();
        assertThat(found.get().getVid()).isEqualTo("cv10086");
    }

    @Test
    @DisplayName("逻辑删除视频")
    void shouldDeleteById() {
        when(mapper.deleteById("vid_123")).thenReturn(1);

        int rows = repository.deleteById("vid_123");

        assertThat(rows).isEqualTo(1);
        verify(mapper).deleteById(eq("vid_123"));
    }
}
