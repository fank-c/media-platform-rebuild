package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.calles.platform.interaction.domain.model.counter.CounterType;
import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoCounterPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoCounterMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频公开互动统计快照仓储 MyBatis-Plus 实现类。
 *
 * <p>基于 {@link VideoCounterMapper} 提供已汇总快照的单条/批量查询，以及后台汇总用例的原子累加落地。</p>
 */
@Repository
@RequiredArgsConstructor
public class VideoCounterRepositoryImpl implements VideoCounterRepository {

    /** 视频公开互动统计快照 Mapper。 */
    private final VideoCounterMapper mapper;

    /**
     * 查询已汇总的 MySQL 计数快照。
     *
     * @param vid 视频公开业务短码
     * @return 统计计数；无记录时返回全零实体
     */
    @Override
    public Optional<VideoCounter> findByVid(String vid) {
        // 步骤 1: 业务短码有效性防御
        if (vid == null || vid.isBlank()) {
            return Optional.empty();
        }

        // 步骤 2: 主键检索并转换为领域对象，查无记录时兜底默认零值对象
        VideoCounterPO po = mapper.selectById(vid.trim());
        return Optional.of(po != null ? po.toDomain() : VideoCounter.createDefault(vid.trim()));
    }

    /**
     * 批量读取已汇总计数快照。
     *
     * @param vids 视频短码集合
     * @return 计数快照列表
     */
    @Override
    public List<VideoCounter> findByVids(Collection<String> vids) {
        // 步骤 1: 空入参防御
        if (vids == null || vids.isEmpty()) {
            return List.of();
        }

        // 步骤 2: 批量主键查询并转换为领域对象列表
        List<VideoCounterPO> pos = mapper.selectBatchIds(vids);
        return pos == null ? List.of() : pos.stream().map(VideoCounterPO::toDomain).toList();
    }

    /**
     * 原子累加指定维度的计数快照。
     *
     * @param vid 视频业务短码
     * @param type 计数维度类型
     * @param delta 净变动量
     */
    @Override
    public void applyDelta(String vid, CounterType type, long delta) {
        // 步骤 1: 防御无效参数与零变动
        if (vid == null || vid.isBlank() || type == null || delta == 0) {
            return;
        }

        // 步骤 2: 路由到对应维度的原子更新 Mapper 方法
        switch (type) {
            case VIEW -> mapper.applyViewDelta(vid.trim(), delta);
            case LIKE -> mapper.applyLikeDelta(vid.trim(), delta);
            case STAR -> mapper.applyStarDelta(vid.trim(), delta);
            case SHARE -> mapper.applyShareDelta(vid.trim(), delta);
        }
    }
}
