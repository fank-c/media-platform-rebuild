package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.tag.VideoTagRel;
import com.calles.platform.content.infrastructure.persistence.entity.VideoTagRelPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoTagRelMapper;
import java.util.List;
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

/**
 * VideoTagRelRepositoryImpl 视频-标签关联仓储实现单元测试。
 * <p>
 * 验证基于 VideoTagRelMapper 的批量关联落库、正向查询视频所含标签、倒排根据标签拉取视频分页列表及全量解绑删除。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoTagRelRepositoryImpl 关联仓储实现测试")
class VideoTagRelRepositoryImplTest {

    /**
     * 模拟 MyBatis-Plus 关联表持久化 Mapper。
     */
    @Mock
    private VideoTagRelMapper mapper;

    /**
     * 被测关联仓储实现。
     */
    @InjectMocks
    private VideoTagRelRepositoryImpl repository;

    /**
     * 测试批量插入视频与标签的绑定关联记录。
     */
    @Test
    @DisplayName("批量保存视频-标签关联记录")
    void shouldBatchInsert() {
        // 步骤 1: 构建两条领域关联实体
        VideoTagRel r1 = VideoTagRel.create("rel_001", "v001", "tag_010");
        VideoTagRel r2 = VideoTagRel.create("rel_002", "v001", "tag_020");

        when(mapper.batchInsert(any())).thenReturn(2);

        // 步骤 2: 调用仓储批量插入
        int inserted = repository.batchInsert(List.of(r1, r2));

        // 步骤 3: 验证插入总数
        assertThat(inserted).isEqualTo(2);
        verify(mapper).batchInsert(any());
    }

    /**
     * 测试正向查询：根据指定视频 ID 检索所绑定的所有标签 ID 列表。
     */
    @Test
    @DisplayName("根据视频 ID 查询绑定的标签 ID 列表")
    void shouldFindTagIdsByVideoId() {
        // 步骤 1: 模拟 Mapper 返回标签 ID 集合
        when(mapper.selectTagIdsByVideoId("v001")).thenReturn(List.of("tag_010", "tag_020", "tag_030"));

        // 步骤 2: 调用仓储查询
        List<String> tagIds = repository.findTagIdsByVideoId("v001");

        // 步骤 3: 断言返回列表内容
        assertThat(tagIds).containsExactly("tag_010", "tag_020", "tag_030");
    }

    /**
     * 测试倒排检索：根据指定标签 ID 分页拉取命中的视频 ID 集合。
     */
    @Test
    @DisplayName("倒排查询：根据标签 ID 分页查询视频 ID 列表")
    void shouldFindVideoIdsByTagId() {
        // 步骤 1: 模拟倒排索引分页返回视频 ID
        when(mapper.selectVideoIdsByTagId("tag_010", 0, 20)).thenReturn(List.of("v001", "v002"));

        // 步骤 2: 调用仓储分页倒排查询
        List<String> videoIds = repository.findVideoIdsByTagId("tag_010", 0, 20);

        // 步骤 3: 断言视频 ID 列表准确无误
        assertThat(videoIds).containsExactly("v001", "v002");
    }

    /**
     * 测试解除指定视频的所有标签绑定关系。
     */
    @Test
    @DisplayName("根据视频 ID 删除全部标签关联")
    void shouldDeleteByVideoId() {
        // 步骤 1: 模拟删除关联行数
        when(mapper.deleteByVideoId("v001")).thenReturn(3);

        // 步骤 2: 调用仓储按视频删除
        int rows = repository.deleteByVideoId("v001");

        // 步骤 3: 验证删除行数与 Mapper 交互
        assertThat(rows).isEqualTo(3);
        verify(mapper).deleteByVideoId(eq("v001"));
    }

    /**
     * 测试精准解除指定视频的特定部分标签关联绑定关系（参数排序防死锁）。
     */
    @Test
    @DisplayName("根据视频 ID 与标签 ID 列表精准批量删除关联：参数排序防死锁")
    void shouldDeleteByVideoIdAndTagIds() {
        // 步骤 1: 捕获传入 Mapper 的标签主键列表
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        when(mapper.deleteByVideoIdAndTagIds(eq("v001"), captor.capture())).thenReturn(2);

        // 步骤 2: 传入无序列表执行删除
        int rows = repository.deleteByVideoIdAndTagIds("v001", List.of("tag_003", "tag_001"));

        // 步骤 3: 验证删除行数并断言主键集合按字典序升序传递
        assertThat(rows).isEqualTo(2);
        assertThat(captor.getValue()).containsExactly("tag_001", "tag_003");
    }
}
