package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoSnapshotPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 视频元数据本地快照持久层 Mapper 接口。
 *
 * <p>写入路径由应用服务在本地事务内按"查询 - 比较版本 - 插入或更新"推进，
 * {@code uk_video_snapshot_source_event} 唯一键承担事件级幂等。</p>
 */
@Mapper
public interface VideoSnapshotMapper extends BaseMapper<VideoSnapshotPO> {

    /**
     * 统计指定来源事件是否已落库，用于消费幂等前置判定。
     *
     * @param sourceEventId 来源事件 ID
     * @return 命中条数 (0 或 1)
     */
    @Select("SELECT COUNT(1) FROM interaction_video_snapshot WHERE source_event_id = #{sourceEventId}")
    int countBySourceEventId(@Param("sourceEventId") String sourceEventId);

    /**
     * 插入全新的视频元数据快照。
     *
     * @param po 待插入的快照持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO interaction_video_snapshot
                (vid, duration, metadata_version, source_event_id, status, updated_at)
            VALUES
                (#{po.vid}, #{po.duration}, #{po.metadataVersion}, #{po.sourceEventId}, #{po.status}, #{po.updatedAt})
            """)
    int insertSnapshot(@Param("po") VideoSnapshotPO po);

    /**
     * 原子条件更新已有视频元数据快照。
     *
     * <p>仅当入参版本大于等于数据库当前记录的版本时才允许更新，利用 MySQL 行级排他锁构筑原子 CAS 防线，
     * 杜绝多消费者并发处理乱序版本事件时的降级回退问题。</p>
     *
     * @param po 待写入的快照持久化对象
     * @return 影响行数（1 表示成功，0 表示被更高版本抢占拒绝更新）
     */
    @Update("""
            UPDATE interaction_video_snapshot SET
                duration = #{po.duration},
                metadata_version = #{po.metadataVersion},
                source_event_id = #{po.sourceEventId},
                status = #{po.status},
                updated_at = #{po.updatedAt}
            WHERE vid = #{po.vid}
              AND metadata_version <= #{po.metadataVersion}
            """)
    int updateIfNewerOrSameVersion(@Param("po") VideoSnapshotPO po);
}
