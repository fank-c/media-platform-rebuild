package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.interaction.domain.model.like.LikeStatus;
import com.calles.platform.interaction.domain.model.like.VideoLike;
import com.calles.platform.interaction.domain.repository.VideoLikeRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoLikePO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoLikeMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频点赞仓储实现类。
 */
@Repository
@RequiredArgsConstructor
public class VideoLikeRepositoryImpl implements VideoLikeRepository {

    private final VideoLikeMapper mapper;

    @Override
    public Optional<VideoLike> findByUserAndVid(String userId, String vid) {
        if (userId == null || vid == null) {
            return Optional.empty();
        }
        LambdaQueryWrapper<VideoLikePO> wrapper = new LambdaQueryWrapper<VideoLikePO>()
                .eq(VideoLikePO::getUserId, userId)
                .eq(VideoLikePO::getVid, vid);
        VideoLikePO po = mapper.selectOne(wrapper);
        return Optional.ofNullable(po).map(VideoLikePO::toDomain);
    }

    @Override
    public List<VideoLike> findActivePageByUserId(String userId, int offset, int limit) {
        if (userId == null || userId.isBlank() || offset < 0 || limit <= 0) {
            return List.of();
        }
        // 构造用户有效点赞查询条件，主排序为点赞时间倒序，次级排序按主键 UUID 倒序保障翻页稳定性
        LambdaQueryWrapper<VideoLikePO> wrapper = new LambdaQueryWrapper<VideoLikePO>()
                .eq(VideoLikePO::getUserId, userId)
                .eq(VideoLikePO::getStatus, LikeStatus.ACTIVE.getValue())
                .orderByDesc(VideoLikePO::getCreatedAt)
                .orderByDesc(VideoLikePO::getId)
                .last("LIMIT " + offset + ", " + limit);
        List<VideoLikePO> pos = mapper.selectList(wrapper);
        if (pos == null) {
            return List.of();
        }
        return pos.stream().map(VideoLikePO::toDomain).toList();
    }

    @Override
    public boolean isLiked(String userId, String vid) {
        if (userId == null || vid == null) {
            return false;
        }
        LambdaQueryWrapper<VideoLikePO> wrapper = new LambdaQueryWrapper<VideoLikePO>()
                .eq(VideoLikePO::getUserId, userId)
                .eq(VideoLikePO::getVid, vid)
                .eq(VideoLikePO::getStatus, LikeStatus.ACTIVE.getValue());
        return mapper.selectCount(wrapper) > 0;
    }

    @Override
    public void save(VideoLike like) {
        if (like == null) {
            return;
        }
        mapper.insert(VideoLikePO.fromDomain(like));
    }

    @Override
    public void update(VideoLike like) {
        if (like == null) {
            return;
        }
        mapper.updateById(VideoLikePO.fromDomain(like));
    }
}
