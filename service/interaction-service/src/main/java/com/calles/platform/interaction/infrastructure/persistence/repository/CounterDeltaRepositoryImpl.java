package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.calles.platform.interaction.domain.model.counter.CounterDelta;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.InteractionCounterDeltaPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.InteractionCounterDeltaMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 视频公开互动统计增量仓储 MyBatis-Plus 实现类。
 *
 * <p>负责在业务事务内持久化追加增量客观事实，并基于 MyBatis-Plus Mapper 提供后台批处理所需的锁行、标记与清理支持。</p>
 */
@Repository
@RequiredArgsConstructor
public class CounterDeltaRepositoryImpl implements CounterDeltaRepository {

    private final InteractionCounterDeltaMapper deltaMapper;

    /**
     * 在当前业务事务内持久化追加一条计数增量记录。
     *
     * @param delta 增量领域实体
     * @throws IllegalStateException 若当前未处于活跃事务中
     * @throws IllegalArgumentException 若增量实体参数非法
     */
    @Override
    public void append(CounterDelta delta) {
        // 步骤 1: 强制检查当前是否存在活跃数据库事务，保证增量与业务状态、Outbox 同事务提交或回滚
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("计数增量必须在业务事务内写入");
        }

        // 步骤 2: 校验增量参数合法性
        if (delta == null || delta.getVid() == null || delta.getVid().isBlank()
                || delta.getType() == null || delta.getDelta() == 0
                || delta.getSourceType() == null || delta.getSourceType().isBlank()
                || delta.getSourceId() == null || delta.getSourceId().isBlank()) {
            throw new IllegalArgumentException("无效的计数增量实体");
        }

        // 步骤 3: 领域实体转换为 PO 并通过 MyBatis-Plus 插入数据库
        InteractionCounterDeltaPO po = InteractionCounterDeltaPO.fromDomain(delta);
        deltaMapper.insert(po);
    }

    /**
     * 悲观顺序锁定当前未处理的待汇总增量列表。
     *
     * @param limit 单次最大锁定数量
     * @return 待汇总增量实体列表
     */
    @Override
    public List<CounterDelta> lockPendingForUpdate(int limit) {
        // 步骤 1: 边界防御规整 limit
        int safeLimit = Math.max(1, limit);

        // 步骤 2: 执行悲观锁查询并转换为领域实体列表
        List<InteractionCounterDeltaPO> pos = deltaMapper.lockPendingForUpdate(safeLimit);
        if (pos == null || pos.isEmpty()) {
            return List.of();
        }
        return pos.stream().map(InteractionCounterDeltaPO::toDomain).toList();
    }

    /**
     * 批量标记增量记录为已汇总状态。
     *
     * @param ids 已汇总增量 ID 集合
     * @param processedAt 汇总完成时间
     */
    @Override
    public void markProcessed(List<Long> ids, LocalDateTime processedAt) {
        // 步骤 1: 空集合防御
        if (ids == null || ids.isEmpty()) {
            return;
        }

        // 步骤 2: 委托 Mapper 批量更新处理完成时间戳
        LocalDateTime time = processedAt != null ? processedAt : LocalDateTime.now();
        deltaMapper.markProcessedBatch(ids, time);
    }

    /**
     * 清理超过保留期且已完成汇总的历史增量数据。
     *
     * @param threshold 保留期截止时间点
     * @param limit 单次最大删除行数
     * @return 实际删除行数
     */
    @Override
    public int deleteProcessedBefore(LocalDateTime threshold, int limit) {
        // 步骤 1: 入参校验防御
        if (threshold == null || limit <= 0) {
            return 0;
        }

        // 步骤 2: 执行有限物理清理
        return deltaMapper.deleteProcessedBefore(threshold, limit);
    }
}
