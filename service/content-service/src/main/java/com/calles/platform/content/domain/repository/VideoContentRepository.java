package com.calles.platform.content.domain.repository;

import com.calles.platform.content.domain.model.video.VideoContent;
import java.util.Optional;

/**
 * 视频内容仓储端口接口。
 */
public interface VideoContentRepository {

    /**
     * 新增视频记录。
     *
     * @param video 待保存的视频聚合根
     * @return 影响行数
     */
    int insert(VideoContent video);

    /**
     * 按版本与主键更新视频记录（带乐观锁与状态约束）。
     *
     * @param video 待更新的视频实体
     * @return 影响行数；0 表示发生并发冲突或记录不存在
     */
    int updateById(VideoContent video);

    /**
     * 根据内部主键 UUID 查询正常（未逻辑删除）的视频内容。
     *
     * @param id 内部主键 ID
     * @return 视频内容（若存在）
     */
    Optional<VideoContent> findById(String id);

    /**
     * 根据业务短码 vid 查询正常（未逻辑删除）的视频内容。
     *
     * @param vid 业务编码 (如 cv2026090001)
     * @return 视频内容（若存在）
     */
    Optional<VideoContent> findByVid(String vid);

    /**
     * 逻辑删除视频。
     *
     * @param id 内部主键 ID
     * @return 影响行数
     */
    int deleteById(String id);
}
