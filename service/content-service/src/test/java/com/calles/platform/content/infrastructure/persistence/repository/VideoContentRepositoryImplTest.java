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

/**
 * VideoContentRepositoryImpl 视频聚合根仓储实现单元测试。
 * <p>
 * 验证底层 VideoContentMapper 的持久化调用、CAS 乐观锁防并发覆盖更新、实体与 PO 的双向映射以及逻辑删除。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoContentRepositoryImpl 仓储实现测试")
class VideoContentRepositoryImplTest {

    /**
     * 模拟 MyBatis-Plus 视频持久化 Mapper。
     */
    @Mock
    private VideoContentMapper mapper;

    /**
     * 被测仓储实现。
     */
    @InjectMocks
    private VideoContentRepositoryImpl repository;

    /**
     * 样例视频聚合根。
     */
    private VideoContent sampleVideo;

    /**
     * 测试数据初始化。
     */
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

    /**
     * 测试新增视频草稿持久化入库，验证从领域实体到 PO 的字段转换。
     */
    @Test
    @DisplayName("新增视频落库保存")
    void shouldInsertVideo() {
        // 步骤 1: 模拟 Mapper 插入成功
        when(mapper.insert(any(VideoContentPO.class))).thenReturn(1);

        // 步骤 2: 调用仓储插入
        int rows = repository.insert(sampleVideo);

        // 步骤 3: 捕获 PO 并验证各属性转换完整度
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

    /**
     * 测试带 CAS 乐观锁版本号 (revision) 的更新机制。
     */
    @Test
    @DisplayName("按主键更新视频（带乐观锁）")
    void shouldUpdateVideoWithOptimisticLock() {
        // 步骤 1: 模拟底层乐观锁更新成功
        when(mapper.updateWithOptimisticLock(any(VideoContentPO.class))).thenReturn(1);

        // 步骤 2: 修改元数据并调用仓储更新
        sampleVideo.updateMetadata("新标题", "新简介", null, null);
        int rows = repository.updateById(sampleVideo);

        // 步骤 3: 验证影响行数与乐观锁 Mapper 调用
        assertThat(rows).isEqualTo(1);
        verify(mapper).updateWithOptimisticLock(any(VideoContentPO.class));
    }

    /**
     * 测试按内部主键 UUID 查询有效视频实体。
     */
    @Test
    @DisplayName("根据 ID 查询有效视频")
    void shouldFindById() {
        // 步骤 1: 模拟 PO 返回
        VideoContentPO po = VideoContentPO.fromDomain(sampleVideo);
        when(mapper.selectById("vid_123")).thenReturn(po);

        // 步骤 2: 调用仓储根据 ID 查询
        Optional<VideoContent> found = repository.findById("vid_123");

        // 步骤 3: 验证领域实体构建正确性
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("vid_123");
        assertThat(found.get().getVid()).isEqualTo("cv10086");
        assertThat(found.get().getStatus()).isEqualTo(CommonStatus.ACTIVE);
        assertThat(found.get().getPublishStatus()).isEqualTo(PublishStatus.DRAFT);
        assertThat(found.get().getTitle()).isEqualTo("微服务入门");
    }

    /**
     * 测试根据对外业务短码 vid 精确查询视频。
     */
    @Test
    @DisplayName("根据业务 vid 查询视频")
    void shouldFindByVid() {
        // 步骤 1: 模拟按 vid 查询返回 PO
        VideoContentPO po = VideoContentPO.fromDomain(sampleVideo);
        when(mapper.selectByVid("cv10086")).thenReturn(po);

        // 步骤 2: 调用仓储按 vid 查询
        Optional<VideoContent> found = repository.findByVid("cv10086");

        // 步骤 3: 验证结果存在且 vid 匹配
        assertThat(found).isPresent();
        assertThat(found.get().getVid()).isEqualTo("cv10086");
    }

    /**
     * 测试按内部主键逻辑删除视频记录。
     */
    @Test
    @DisplayName("逻辑删除视频")
    void shouldDeleteById() {
        // 步骤 1: 模拟 Mapper 逻辑删除成功
        when(mapper.deleteById("vid_123")).thenReturn(1);

        // 步骤 2: 调用仓储删除
        int rows = repository.deleteById("vid_123");

        // 步骤 3: 验证影响行数与 Mapper 调用参数
        assertThat(rows).isEqualTo(1);
        verify(mapper).deleteById(eq("vid_123"));
    }
}
