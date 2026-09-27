package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.like.VideoLike;
import java.util.List;
import java.util.Optional;

/**
 * 视频点赞实体仓储接口。
 */
public interface VideoLikeRepository {

    /**
     * 查询指定用户对特定视频的点赞记录。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @return 点赞实体 (若存在)
     */
    Optional<VideoLike> findByUserAndVid(String userId, String vid);

    /**
     * 分页查询用户有效点赞的记录列表（按点赞时间倒序）。
     *
     * @param userId 用户账号 ID
     * @param offset 分页起始偏移量 (>= 0)
     * @param limit 每页限制条数 (> 0)
     * @return 有效点赞实体列表
     */
    List<VideoLike> findActivePageByUserId(String userId, int offset, int limit);

    /**
     * 判断用户是否有效点赞了该视频。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @return true 若处于有效点赞状态
     */
    boolean isLiked(String userId, String vid);

    /**
     * 保存点赞实体（新增）。
     *
     * @param like 点赞实体
     */
    void save(VideoLike like);

    /**
     * 更新点赞实体状态（取消或重新激活）。
     *
     * @param like 点赞实体
     */
    void update(VideoLike like);
}
