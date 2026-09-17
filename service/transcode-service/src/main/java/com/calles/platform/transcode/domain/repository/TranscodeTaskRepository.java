package com.calles.platform.transcode.domain.repository;

import com.calles.platform.transcode.domain.model.MediaFormat;
import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.domain.model.TranscodeTask;
import com.calles.platform.transcode.domain.model.TranscodeTaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 转码工单领域仓储接口 (TranscodeTaskRepository)。
 *
 * <p>屏蔽底层持久化实现细节，定义纯净领域持久化契约。</p>
 */
public interface TranscodeTaskRepository {

    /**
     * 保存新建转码工单。
     */
    int insert(TranscodeTask task);

    /**
     * 更新已有转码工单状态与产物信息。
     */
    int updateById(TranscodeTask task);

    /**
     * 根据内部唯一主键 ID 查询工单。
     */
    Optional<TranscodeTask> findById(String id);

    /**
     * 根据视频 ID、目标规格和封装格式查询既有工单（用于消费去重与幂等判断）。
     */
    Optional<TranscodeTask> findBySpec(String videoId, QualityPreset quality, MediaFormat format);

    /**
     * 根据视频 ID 查询该视频名下的所有规格转码工单。
     */
    List<TranscodeTask> findByVideoId(String videoId);

    /**
     * 查询指定状态且在某时间之前创建/更新的未完结任务（供超时自愈补偿使用）。
     */
    List<TranscodeTask> findStaleTasks(TranscodeTaskStatus status, LocalDateTime beforeTime, int limit);
}
