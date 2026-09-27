package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.video.VideoSnapshot;
import java.util.Optional;

/**
 * 视频元数据本地快照仓储接口。
 */
public interface VideoSnapshotRepository {

    /**
     * 按视频编码读取本地时长快照。
     *
     * @param vid 视频公开短码
     * @return 快照实体；无快照时返回空
     */
    Optional<VideoSnapshot> findByVid(String vid);

    /**
     * 判断指定来源事件是否已成功消费。
     *
     * @param sourceEventId 来源事件 ID
     * @return true 表示该事件已处理过，应直接幂等跳过
     */
    boolean existsBySourceEventId(String sourceEventId);

    /**
     * 按"同版本或更高版本元数据"规则写入快照。
     *
     * <p>低版本事件到达时保持既有快照不变，避免乱序消费导致时长回退。</p>
     *
     * @param snapshot 待写入的快照
     * @return true 表示实际写入或更新成功；false 表示因版本回退被跳过
     */
    boolean saveIfNewerOrSameVersion(VideoSnapshot snapshot);
}
