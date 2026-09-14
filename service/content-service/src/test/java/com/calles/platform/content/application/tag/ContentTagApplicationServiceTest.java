package com.calles.platform.content.application.tag;

import com.calles.platform.content.domain.model.CommonStatus;
import com.calles.platform.content.domain.model.tag.ContentTag;
import com.calles.platform.content.domain.model.tag.VideoTagRel;
import com.calles.platform.content.domain.repository.ContentTagRepository;
import com.calles.platform.content.domain.repository.VideoTagRelRepository;
import java.util.Collections;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContentTagApplicationService 标签应用服务单元测试。
 * <p>
 * 覆盖增量精准差集同步、批量热度更新防死锁、多标签创建、精准批量解绑及参数边界等业务场景。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentTagApplicationService 标签应用服务测试")
class ContentTagApplicationServiceTest {

    @Mock
    private ContentTagRepository contentTagRepository;

    @Mock
    private VideoTagRelRepository videoTagRelRepository;

    @InjectMocks
    private ContentTagApplicationService contentTagApplicationService;

    @Test
    @DisplayName("syncVideoTags：当 videoId 为空时直接短路返回")
    void shouldReturnEarlyWhenVideoIdIsBlank() {
        // 步骤 1: 传入空 videoId
        contentTagApplicationService.syncVideoTags("", "Java,Spring");
        contentTagApplicationService.syncVideoTags(null, "Java,Spring");

        // 步骤 2: 验证未触发任何仓储访问
        verify(videoTagRelRepository, never()).findTagIdsByVideoId(any());
        verify(contentTagRepository, never()).batchUpdateReferenceCount(any(), anyLong());
    }

    @Test
    @DisplayName("syncVideoTags：新旧标签无任何差异时，直接返回不产生多余写操作")
    void shouldDoNothingWhenTagsAreIdentical() {
        // 步骤 1: 模拟当前已有标签 [Java, Spring]
        ContentTag tagJava = ContentTag.builder().id("tag_001").name("Java").referenceCount(10L).status(CommonStatus.ACTIVE).build();
        ContentTag tagSpring = ContentTag.builder().id("tag_002").name("Spring").referenceCount(5L).status(CommonStatus.ACTIVE).build();

        when(videoTagRelRepository.findTagIdsByVideoId("vid_100")).thenReturn(List.of("tag_001", "tag_002"));
        when(contentTagRepository.findByIds(List.of("tag_001", "tag_002"))).thenReturn(List.of(tagJava, tagSpring));

        // 步骤 2: 传入相同标签（包含全角逗号与空格）
        contentTagApplicationService.syncVideoTags("vid_100", "Java， Spring ");

        // 步骤 3: 验证未发生任何写库与更新
        verify(contentTagRepository, never()).findOrCreateBatch(any());
        verify(contentTagRepository, never()).batchUpdateReferenceCount(any(), anyLong());
        verify(videoTagRelRepository, never()).batchInsert(any());
        verify(videoTagRelRepository, never()).deleteByVideoIdAndTagIds(any(), any());
        verify(videoTagRelRepository, never()).deleteByVideoId(any());
    }

    @Test
    @DisplayName("syncVideoTags：纯新增标签时，批量创建、单次批量自增热度并批量插入关系")
    void shouldHandlePureAddTags() {
        // 步骤 1: 模拟当前视频无任何标签
        when(videoTagRelRepository.findTagIdsByVideoId("vid_100")).thenReturn(Collections.emptyList());
        when(contentTagRepository.findByIds(Collections.emptyList())).thenReturn(Collections.emptyList());

        ContentTag tagJava = ContentTag.builder().id("tag_001").name("Java").referenceCount(0L).status(CommonStatus.ACTIVE).build();
        ContentTag tagGo = ContentTag.builder().id("tag_002").name("Go").referenceCount(0L).status(CommonStatus.ACTIVE).build();
        when(contentTagRepository.findOrCreateBatch(any())).thenReturn(List.of(tagJava, tagGo));

        // 步骤 2: 同步新增两个标签
        contentTagApplicationService.syncVideoTags("vid_100", "Java, Go");

        // 步骤 3: 验证批量自增热度 (+1L) 与批量插入
        verify(contentTagRepository).batchUpdateReferenceCount(eq(List.of("tag_001", "tag_002")), eq(1L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VideoTagRel>> captor = ArgumentCaptor.forClass(List.class);
        verify(videoTagRelRepository).batchInsert(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).extracting(VideoTagRel::getTagId).containsExactlyInAnyOrder("tag_001", "tag_002");

        // 步骤 4: 验证未触发解绑
        verify(videoTagRelRepository, never()).deleteByVideoIdAndTagIds(any(), any());
    }

    @Test
    @DisplayName("syncVideoTags：纯移除标签时，单次批量自减热度并精准批量解绑")
    void shouldHandlePureRemoveTags() {
        // 步骤 1: 模拟当前已有标签 [Java, Python]
        ContentTag tagJava = ContentTag.builder().id("tag_001").name("Java").referenceCount(10L).status(CommonStatus.ACTIVE).build();
        ContentTag tagPython = ContentTag.builder().id("tag_002").name("Python").referenceCount(8L).status(CommonStatus.ACTIVE).build();

        when(videoTagRelRepository.findTagIdsByVideoId("vid_100")).thenReturn(List.of("tag_001", "tag_002"));
        when(contentTagRepository.findByIds(List.of("tag_001", "tag_002"))).thenReturn(List.of(tagJava, tagPython));

        // 步骤 2: 清空所有标签
        contentTagApplicationService.syncVideoTags("vid_100", "");

        // 步骤 3: 验证批量递减热度 (-1L) 与精准解绑
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> removeTagIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(contentTagRepository).batchUpdateReferenceCount(removeTagIdsCaptor.capture(), eq(-1L));
        assertThat(removeTagIdsCaptor.getValue()).containsExactlyInAnyOrder("tag_001", "tag_002");

        verify(videoTagRelRepository).deleteByVideoIdAndTagIds(eq("vid_100"), eq(removeTagIdsCaptor.getValue()));

        // 步骤 4: 验证未触发新增插入
        verify(contentTagRepository, never()).findOrCreateBatch(any());
        verify(videoTagRelRepository, never()).batchInsert(any());
    }

    @Test
    @DisplayName("syncVideoTags：增删混合场景，精准增量处理且未变动标签不受影响")
    void shouldHandleMixedAddAndRemoveIncrementally() {
        // 步骤 1: 模拟当前标签为 [Java, Python]
        ContentTag tagJava = ContentTag.builder().id("tag_001").name("Java").referenceCount(10L).status(CommonStatus.ACTIVE).build();
        ContentTag tagPython = ContentTag.builder().id("tag_002").name("Python").referenceCount(8L).status(CommonStatus.ACTIVE).build();
        ContentTag tagGo = ContentTag.builder().id("tag_003").name("Go").referenceCount(0L).status(CommonStatus.ACTIVE).build();

        when(videoTagRelRepository.findTagIdsByVideoId("vid_100")).thenReturn(List.of("tag_001", "tag_002"));
        when(contentTagRepository.findByIds(List.of("tag_001", "tag_002"))).thenReturn(List.of(tagJava, tagPython));

        // 模拟对新增项 Go 的批量获取
        when(contentTagRepository.findOrCreateBatch(any())).thenReturn(List.of(tagGo));

        // 步骤 2: 更新标签为 [Java, Go]（移除 Python，新增 Go，保留 Java）
        contentTagApplicationService.syncVideoTags("vid_100", "Java, Go");

        // 步骤 3: 验证新增 Go 的批量自增 (+1L) 与插入
        verify(contentTagRepository).batchUpdateReferenceCount(eq(List.of("tag_003")), eq(1L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VideoTagRel>> insertCaptor = ArgumentCaptor.forClass(List.class);
        verify(videoTagRelRepository).batchInsert(insertCaptor.capture());
        assertThat(insertCaptor.getValue()).hasSize(1);
        assertThat(insertCaptor.getValue().get(0).getTagId()).isEqualTo("tag_003");

        // 步骤 4: 验证移除 Python 的批量自减 (-1L) 与精准解绑
        verify(contentTagRepository).batchUpdateReferenceCount(eq(List.of("tag_002")), eq(-1L));
        verify(videoTagRelRepository).deleteByVideoIdAndTagIds(eq("vid_100"), eq(List.of("tag_002")));

        // 步骤 5: 核心断言：全量清空 deleteByVideoId 绝对没有被调用
        verify(videoTagRelRepository, never()).deleteByVideoId(any());
    }

    @Test
    @DisplayName("getTagNamesByVideoId：成功根据视频主键拉取关联标签文本列表")
    void shouldGetTagNamesByVideoId() {
        ContentTag tagJava = ContentTag.builder().id("tag_001").name("Java").referenceCount(10L).status(CommonStatus.ACTIVE).build();
        when(videoTagRelRepository.findTagIdsByVideoId("vid_100")).thenReturn(List.of("tag_001"));
        when(contentTagRepository.findByIds(List.of("tag_001"))).thenReturn(List.of(tagJava));

        List<String> names = contentTagApplicationService.getTagNamesByVideoId("vid_100");
        assertThat(names).containsExactly("Java");
    }

    @Test
    @DisplayName("getHotTags：获取热门标签并自动施加上限边界保护")
    void shouldGetHotTagsWithBoundedLimit() {
        ContentTag tagJava = ContentTag.builder().id("tag_001").name("Java").referenceCount(100L).status(CommonStatus.ACTIVE).build();
        when(contentTagRepository.findTopHotTags(50)).thenReturn(List.of(tagJava));

        List<ContentTag> tags = contentTagApplicationService.getHotTags(100);
        assertThat(tags).hasSize(1);
        verify(contentTagRepository).findTopHotTags(50);
    }
}
