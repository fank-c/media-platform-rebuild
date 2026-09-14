package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.tag.VideoTagRel;
import com.calles.platform.content.infrastructure.persistence.entity.VideoTagRelPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoTagRelMapper;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoTagRelRepositoryImpl 关联仓储实现测试")
class VideoTagRelRepositoryImplTest {

    @Mock
    private VideoTagRelMapper mapper;

    @InjectMocks
    private VideoTagRelRepositoryImpl repository;

    @Test
    @DisplayName("批量保存视频-标签关联记录")
    void shouldBatchInsert() {
        VideoTagRel r1 = VideoTagRel.create("rel_001", "v001", "tag_010");
        VideoTagRel r2 = VideoTagRel.create("rel_002", "v001", "tag_020");

        when(mapper.insert(any(VideoTagRelPO.class))).thenReturn(1);

        int inserted = repository.batchInsert(List.of(r1, r2));

        assertThat(inserted).isEqualTo(2);
        assertThat(r1.getId()).isEqualTo("rel_001");
        assertThat(r2.getId()).isEqualTo("rel_002");
    }

    @Test
    @DisplayName("根据视频 ID 查询绑定的标签 ID 列表")
    void shouldFindTagIdsByVideoId() {
        when(mapper.selectTagIdsByVideoId("v001")).thenReturn(List.of("tag_010", "tag_020", "tag_030"));

        List<String> tagIds = repository.findTagIdsByVideoId("v001");

        assertThat(tagIds).containsExactly("tag_010", "tag_020", "tag_030");
    }

    @Test
    @DisplayName("倒排查询：根据标签 ID 分页查询视频 ID 列表")
    void shouldFindVideoIdsByTagId() {
        when(mapper.selectVideoIdsByTagId("tag_010", 0, 20)).thenReturn(List.of("v001", "v002"));

        List<String> videoIds = repository.findVideoIdsByTagId("tag_010", 0, 20);

        assertThat(videoIds).containsExactly("v001", "v002");
    }

    @Test
    @DisplayName("根据视频 ID 删除全部标签关联")
    void shouldDeleteByVideoId() {
        when(mapper.deleteByVideoId("v001")).thenReturn(3);

        int rows = repository.deleteByVideoId("v001");

        assertThat(rows).isEqualTo(3);
        verify(mapper).deleteByVideoId(eq("v001"));
    }
}
