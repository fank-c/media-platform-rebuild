package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.task.TaskStatus;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.task.VideoTask;
import com.calles.platform.content.infrastructure.persistence.entity.VideoTaskPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoTaskMapper;
import java.time.LocalDateTime;
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

/**
 * 视频异步任务仓储实现类 {@link VideoTaskRepositoryImpl} 单元测试。
 *
 * <p>验证仓储实现类对底层 {@link VideoTaskMapper} 的委托调用、PO 与 Domain 实体的转换及空值保护。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoTaskRepositoryImpl 仓储实现测试")
class VideoTaskRepositoryImplTest {

    /** 底层模拟的数据访问 Mapper。 */
    @Mock
    private VideoTaskMapper mapper;

    /** 待测试的仓储实现类（自动注入 mock mapper）。 */
    @InjectMocks
    private VideoTaskRepositoryImpl repository;

    /**
     * 测试单条任务插入并回写生成的实体主键。
     */
    @Test
    @DisplayName("插入单条任务记录并回写主键")
    void shouldInsertTask() {
        // 步骤 1 (Given)：构建待插入的领域实体并模拟 Mapper 插入成功
        VideoTask task = VideoTask.create("v_100", TaskType.AUDIT);
        when(mapper.insert(any(VideoTaskPO.class))).thenReturn(1);

        // 步骤 2 (When)：调用仓储插入
        repository.insert(task);

        // 步骤 3 (Then)：验证 Mapper 被正确调用且任务 ID 依然有效
        verify(mapper).insert(any(VideoTaskPO.class));
        assertThat(task.getId()).isNotNull();
    }

    /**
     * 测试批量插入任务记录能够逐条遍历持久化。
     */
    @Test
    @DisplayName("批量插入任务记录")
    void shouldBatchInsertTasks() {
        // 步骤 1 (Given)：构建包含 2 个任务实体的列表并模拟 Mapper
        VideoTask t1 = VideoTask.create("v_100", TaskType.AUDIT);
        VideoTask t2 = VideoTask.create("v_100", TaskType.TRANSCODE_720P);
        when(mapper.insert(any(VideoTaskPO.class))).thenReturn(1);

        // 步骤 2 (When)：执行批量插入
        repository.batchInsert(List.of(t1, t2));

        // 步骤 3 (Then)：断言 Mapper 的 insert 方法被执行了 2 次
        verify(mapper, org.mockito.Mockito.times(2)).insert(any(VideoTaskPO.class));
    }

    /**
     * 测试根据视频 ID 查询出全量 PO 并正确反序列化为领域实体列表。
     */
    @Test
    @DisplayName("根据视频 ID 查询所有子任务列表")
    void shouldFindByVideoId() {
        // 步骤 1 (Given)：模拟 Mapper 返回持久化 PO 列表
        VideoTaskPO po = VideoTaskPO.builder()
                .id("t_1")
                .videoId("v_100")
                .taskType("AUDIT")
                .status("SUCCESS")
                .progress(100)
                .build();
        when(mapper.selectByVideoId("v_100")).thenReturn(List.of(po));

        // 步骤 2 (When)：调用仓储查询
        List<VideoTask> tasks = repository.findByVideoId("v_100");

        // 步骤 3 (Then)：断言领域模型列表转换正确
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTaskType()).isEqualTo(TaskType.AUDIT);
        assertThat(tasks.get(0).getStatus()).isEqualTo(TaskStatus.SUCCESS);
    }

    /**
     * 测试根据视频 ID 与任务类型精确查询单个子任务。
     */
    @Test
    @DisplayName("根据视频 ID 和任务类型精确查询")
    void shouldFindByVideoIdAndTaskType() {
        // 步骤 1 (Given)：模拟精准命中一条 1080P 转码 PO
        VideoTaskPO po = VideoTaskPO.builder()
                .id("t_1")
                .videoId("v_100")
                .taskType("TRANSCODE_1080P")
                .status("RUNNING")
                .progress(60)
                .build();
        when(mapper.selectByVideoIdAndTaskType("v_100", "TRANSCODE_1080P")).thenReturn(po);

        // 步骤 2 (When)：调用精确查询
        Optional<VideoTask> taskOpt = repository.findByVideoIdAndTaskType("v_100", TaskType.TRANSCODE_1080P);

        // 步骤 3 (Then)：断言正确封装为 Optional 并包含 60% 进度
        assertThat(taskOpt).isPresent();
        assertThat(taskOpt.get().getProgress()).isEqualTo(60);
    }

    /**
     * 测试检索超时任务列表并正确映射为领域实体集合。
     */
    @Test
    @DisplayName("查找超时任务列表")
    void shouldFindTimeoutTasks() {
        // 步骤 1 (Given)：设置 15 分钟回溯阈值并模拟命中超时记录
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(15);
        VideoTaskPO po = VideoTaskPO.builder()
                .id("t_1")
                .videoId("v_100")
                .taskType("TRANSCODE_4K")
                .status("RUNNING")
                .startedAt(threshold.minusMinutes(5))
                .build();
        when(mapper.selectTimeoutTasks(eq("RUNNING"), any(LocalDateTime.class))).thenReturn(List.of(po));

        // 步骤 2 (When)：调用超时检索
        List<VideoTask> timeoutTasks = repository.findTimeoutTasks(TaskStatus.RUNNING, threshold);

        // 步骤 3 (Then)：断言返回匹配的超时领域实体
        assertThat(timeoutTasks).hasSize(1);
        assertThat(timeoutTasks.get(0).getId()).isEqualTo("t_1");
    }
}
