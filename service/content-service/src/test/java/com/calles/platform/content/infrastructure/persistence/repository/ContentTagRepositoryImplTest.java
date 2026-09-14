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
 * ContentTagRepositoryImpl 标签仓储实现单元测试。
 * <p>
 * 验证基于 ContentTagMapper 的底层交互，包括精准名称查询、并发安全的 findOrCreate 保证、引用热度原子更新及全站热门标签检索。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentTagRepositoryImpl 标签仓储实现测试")
class ContentTagRepositoryImplTest {

    /**
     * 模拟 MyBatis-Plus 标签 Mapper。
     */
    @Mock
    private ContentTagMapper contentTagMapper;

    /**
     * 被测标签仓储实现。
     */
    @InjectMocks
    private ContentTagRepositoryImpl repository;

    /**
     * 测试按名称精确查询标签领域实体转换。
     */
    @Test
    @DisplayName("按名称查询标签")
    void shouldFindByName() {
        // 步骤 1: 模拟 Mapper 返回持久化 PO
        ContentTagPO po = ContentTagPO.builder()
                .id("tag_010")
                .name("微服务")
                .referenceCount(5L)
                .status("ACTIVE")
                .build();
        when(contentTagMapper.selectByName("微服务")).thenReturn(po);

        // 步骤 2: 调用仓储查询
        Optional<ContentTag> found = repository.findByName("微服务");

        // 步骤 3: 验证 PO 到 Domain 实体的字段映射与状态枚举转换
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("tag_010");
        assertThat(found.get().getName()).isEqualTo("微服务");
        assertThat(found.get().getReferenceCount()).isEqualTo(5L);
        assertThat(found.get().getStatus()).isEqualTo(CommonStatus.ACTIVE);
    }

    /**
     * 测试 findOrCreate 命中已存在标签时的快速返回。
     */
    @Test
    @DisplayName("findOrCreate：已存在则直接返回")
    void shouldReturnExistingOnFindOrCreate() {
        // 步骤 1: 模拟库中已存在该标签
        ContentTagPO existing = ContentTagPO.builder()
                .id("tag_001")
                .name("Java")
                .referenceCount(100L)
                .status("ACTIVE")
                .build();
        when(contentTagMapper.selectByName("Java")).thenReturn(existing);

        // 步骤 2: 调用 findOrCreate
        ContentTag tag = repository.findOrCreate("Java");

        // 步骤 3: 验证直接返回已存在的标签实体
        assertThat(tag.getId()).isEqualTo("tag_001");
        assertThat(tag.getName()).isEqualTo("Java");
        assertThat(tag.getReferenceCount()).isEqualTo(100L);
    }

    /**
     * 测试 findOrCreate 未命中标签时的安全并发写入 (insertIgnore) 与回查重试。
     */
    @Test
    @DisplayName("findOrCreate：不存在则幂等插入并返回")
    void shouldCreateNewOnFindOrCreate() {
        // 步骤 1: 模拟首次查询未命中，插入后二次查询成功
        ContentTagPO created = ContentTagPO.builder()
                .id("tag_002")
                .name("Golang")
                .referenceCount(0L)
                .status("ACTIVE")
                .build();
        when(contentTagMapper.selectByName("Golang")).thenReturn(null).thenReturn(created);
        when(contentTagMapper.insertIgnore(any(), eq("Golang"))).thenReturn(1);

        // 步骤 2: 调用 findOrCreate
        ContentTag tag = repository.findOrCreate("Golang");

        // 步骤 3: 验证触发了 insertIgnore 并成功获取新实体
        assertThat(tag.getId()).isEqualTo("tag_002");
        assertThat(tag.getName()).isEqualTo("Golang");
        verify(contentTagMapper).insertIgnore(any(), eq("Golang"));
    }

    /**
     * 测试原子更新标签的关联热度计数。
     */
    @Test
    @DisplayName("更新引用热度计数")
    void shouldUpdateReferenceCount() {
        // 步骤 1: 模拟底层 Mapper 原子更新受影响行数
        when(contentTagMapper.updateReferenceCount("tag_001", 1L)).thenReturn(1);

        // 步骤 2: 调用仓储更新
        int rows = repository.updateReferenceCount("tag_001", 1L);

        // 步骤 3: 验证影响行数并断言 Mapper 调用
        assertThat(rows).isEqualTo(1);
        verify(contentTagMapper).updateReferenceCount("tag_001", 1L);
    }

    /**
     * 测试获取全站高频热门标签榜单并转换为领域实体列表。
     */
    @Test
    @DisplayName("获取全站热门标签")
    void shouldFindTopHotTags() {
        // 步骤 1: 模拟底层热度排行查询结果
        ContentTagPO p1 = ContentTagPO.builder().id("tag_001").name("Java").referenceCount(50L).status("ACTIVE").build();
        ContentTagPO p2 = ContentTagPO.builder().id("tag_002").name("Spring").referenceCount(30L).status("ACTIVE").build();
        when(contentTagMapper.selectTopHot(10)).thenReturn(List.of(p1, p2));

        // 步骤 2: 查询前 10 个热门标签
        List<ContentTag> hots = repository.findTopHotTags(10);

        // 步骤 3: 验证列表长度与排序保持一致
        assertThat(hots).hasSize(2);
        assertThat(hots.get(0).getName()).isEqualTo("Java");
        assertThat(hots.get(1).getName()).isEqualTo("Spring");
    }

    /**
     * 测试批量更新热度计数：主键升序排列防死锁。
     */
    @Test
    @DisplayName("批量更新引用热度计数：参数升序排列防死锁并原子更新")
    void shouldBatchUpdateReferenceCountWithSortedIds() {
        // 步骤 1: 模拟底层 Mapper 批量更新受影响行数
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        when(contentTagMapper.batchUpdateReferenceCount(captor.capture(), eq(1L))).thenReturn(2);

        // 步骤 2: 传入无序列表进行批量更新
        int rows = repository.batchUpdateReferenceCount(List.of("tag_003", "tag_001", "tag_002"), 1L);

        // 步骤 3: 验证影响行数并断言 Mapper 接收到的主键列表严格升序排列
        assertThat(rows).isEqualTo(2);
        assertThat(captor.getValue()).containsExactly("tag_001", "tag_002", "tag_003");
    }

    /**
     * 测试按名称批量查询标签。
     */
    @Test
    @DisplayName("按名称批量查询标签")
    void shouldFindByNames() {
        ContentTagPO p1 = ContentTagPO.builder().id("tag_001").name("Java").referenceCount(50L).status("ACTIVE").build();
        when(contentTagMapper.selectByNames(any())).thenReturn(List.of(p1));

        List<ContentTag> tags = repository.findByNames(List.of("Java", "Kotlin"));

        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).getName()).isEqualTo("Java");
    }

    /**
     * 测试批量 findOrCreateBatch：部分存在部分不存在时的处理。
     */
    @Test
    @DisplayName("findOrCreateBatch：部分存在部分不存在时幂等补全并返回全量")
    void shouldFindOrCreateBatch() {
        ContentTagPO existing = ContentTagPO.builder().id("tag_001").name("Java").referenceCount(10L).status("ACTIVE").build();
        ContentTagPO created = ContentTagPO.builder().id("tag_002").name("Go").referenceCount(0L).status("ACTIVE").build();

        when(contentTagMapper.selectByNames(any())).thenReturn(List.of(existing)).thenReturn(List.of(existing, created));
        when(contentTagMapper.insertIgnore(any(), eq("Go"))).thenReturn(1);

        List<ContentTag> result = repository.findOrCreateBatch(List.of("Java", "Go"));

        assertThat(result).hasSize(2);
        verify(contentTagMapper).insertIgnore(any(), eq("Go"));
    }
}
