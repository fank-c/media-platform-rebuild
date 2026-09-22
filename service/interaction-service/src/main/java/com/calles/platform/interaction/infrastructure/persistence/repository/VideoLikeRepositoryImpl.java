package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.interaction.domain.model.like.LikeStatus;
import com.calles.platform.interaction.domain.model.like.VideoLike;
import com.calles.platform.interaction.domain.repository.VideoLikeRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoLikePO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoLikeMapper;
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
