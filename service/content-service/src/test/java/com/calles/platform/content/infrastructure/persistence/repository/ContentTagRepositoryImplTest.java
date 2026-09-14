package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.CommonStatus;
import com.calles.platform.content.domain.model.tag.ContentTag;
import com.calles.platform.content.infrastructure.persistence.entity.ContentTagPO;
import com.calles.platform.content.infrastructure.persistence.mapper.ContentTagMapper;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("ContentTagRepositoryImpl 标签仓储实现测试")
class ContentTagRepositoryImplTest {

    @Mock
    private ContentTagMapper contentTagMapper;

    @InjectMocks
    private ContentTagRepositoryImpl repository;

    @Test
    @DisplayName("按名称查询标签")
    void shouldFindByName() {
        ContentTagPO po = ContentTagPO.builder()
                .id("tag_010")
                .name("微服务")
                .referenceCount(5L)
                .status("ACTIVE")
                .build();
        when(contentTagMapper.selectByName("微服务")).thenReturn(po);

        Optional<ContentTag> found = repository.findByName("微服务");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("tag_010");
        assertThat(found.get().getName()).isEqualTo("微服务");
        assertThat(found.get().getReferenceCount()).isEqualTo(5L);
        assertThat(found.get().getStatus()).isEqualTo(CommonStatus.ACTIVE);
    }

    @Test
    @DisplayName("findOrCreate：已存在则直接返回")
    void shouldReturnExistingOnFindOrCreate() {
        ContentTagPO existing = ContentTagPO.builder()
                .id("tag_001")
                .name("Java")
                .referenceCount(100L)
                .status("ACTIVE")
                .build();
        when(contentTagMapper.selectByName("Java")).thenReturn(existing);

        ContentTag tag = repository.findOrCreate("Java");

        assertThat(tag.getId()).isEqualTo("tag_001");
        assertThat(tag.getName()).isEqualTo("Java");
        assertThat(tag.getReferenceCount()).isEqualTo(100L);
    }

    @Test
    @DisplayName("findOrCreate：不存在则幂等插入并返回")
    void shouldCreateNewOnFindOrCreate() {
        ContentTagPO created = ContentTagPO.builder()
                .id("tag_002")
                .name("Golang")
                .referenceCount(0L)
                .status("ACTIVE")
                .build();
        when(contentTagMapper.selectByName("Golang")).thenReturn(null).thenReturn(created);
        when(contentTagMapper.insertIgnore(any(), eq("Golang"))).thenReturn(1);

        ContentTag tag = repository.findOrCreate("Golang");

        assertThat(tag.getId()).isEqualTo("tag_002");
        assertThat(tag.getName()).isEqualTo("Golang");
        verify(contentTagMapper).insertIgnore(any(), eq("Golang"));
    }

    @Test
    @DisplayName("更新引用热度计数")
    void shouldUpdateReferenceCount() {
        when(contentTagMapper.updateReferenceCount("tag_001", 1L)).thenReturn(1);

        int rows = repository.updateReferenceCount("tag_001", 1L);

        assertThat(rows).isEqualTo(1);
        verify(contentTagMapper).updateReferenceCount("tag_001", 1L);
    }

    @Test
    @DisplayName("获取全站热门标签")
    void shouldFindTopHotTags() {
        ContentTagPO p1 = ContentTagPO.builder().id("tag_001").name("Java").referenceCount(50L).status("ACTIVE").build();
        ContentTagPO p2 = ContentTagPO.builder().id("tag_002").name("Spring").referenceCount(30L).status("ACTIVE").build();
        when(contentTagMapper.selectTopHot(10)).thenReturn(List.of(p1, p2));

        List<ContentTag> hots = repository.findTopHotTags(10);

        assertThat(hots).hasSize(2);
        assertThat(hots.get(0).getName()).isEqualTo("Java");
        assertThat(hots.get(1).getName()).isEqualTo("Spring");
    }
}
