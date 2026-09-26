package com.calles.platform.interaction.application.counter;

import com.calles.platform.interaction.domain.model.counter.CounterDelta;
import com.calles.platform.interaction.domain.model.counter.CounterType;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计数增量后台汇总与清理用例。
 *
 * <p>负责将已提交的增量客观事实原子汇总到公开快照，并在同一事务内标记增量已处理；同时提供超期已汇总增量清理能力。
 * 遵循依赖倒置原则，仅依赖领域仓储接口，与持久层具体实现解耦。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounterAggregationApplicationService {

    private final CounterDeltaRepository deltaRepository;
    private final VideoCounterRepository counterRepository;

    /**
     * 批量汇总一批计数增量。
     *
     * @param batchSize 最大领取数量
     * @return 本次成功汇总的增量条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int aggregate(int batchSize) {
        // 步骤 1: 悲观锁顺序认领一批待处理增量（行级锁防多实例重复领取）
        List<CounterDelta> batch = deltaRepository.lockPendingForUpdate(batchSize);
        if (batch == null || batch.isEmpty()) {
            return 0;
        }

        // 步骤 2: 在内存中按 (vid, CounterType) 合并变动量，压缩热点视频的数据库行级更新次数
        Map<Key, Long> totals = new LinkedHashMap<>();
        for (CounterDelta delta : batch) {
            totals.merge(new Key(delta.getVid(), delta.getType()), delta.getDelta(), Math::addExact);
        }

        // 步骤 3: 逐一原子更新公开计数快照
        for (Map.Entry<Key, Long> entry : totals.entrySet()) {
            if (entry.getValue() != 0) {
                counterRepository.applyDelta(entry.getKey().vid(), entry.getKey().type(), entry.getValue());
            }
        }

        // 步骤 4: 同事务标记增量记录为已处理
        List<Long> ids = batch.stream().map(CounterDelta::getId).toList();
        deltaRepository.markProcessed(ids, LocalDateTime.now());

        log.debug("成功批量汇总 [{}] 条计数增量，合并更新 [{}] 个快照维度", batch.size(), totals.size());
        return batch.size();
    }

    /**
     * 清理已经汇总且超过保留期的历史增量记录。
     *
     * @param retentionDays 保留天数
     * @param batchSize 单次最大清理条数
     * @return 实际清理的记录数
     */
    @Transactional(rollbackFor = Exception.class)
    public int cleanupProcessed(int retentionDays, int batchSize) {
        // 步骤 1: 校验防御入参有效性
        if (retentionDays <= 0 || batchSize <= 0) {
            return 0;
        }

        // 步骤 2: 计算超期时间阈值并委托仓储执行清理
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        int deleted = deltaRepository.deleteProcessedBefore(threshold, batchSize);
        if (deleted > 0) {
            log.info("成功清理超过 [{}] 天的已汇总历史计数增量 [{}] 条", retentionDays, deleted);
        }
        return deleted;
    }

    /**
     * 按视频编码与计数类型维度聚合增量的复合键。
     */
    private record Key(String vid, CounterType type) { }
}
