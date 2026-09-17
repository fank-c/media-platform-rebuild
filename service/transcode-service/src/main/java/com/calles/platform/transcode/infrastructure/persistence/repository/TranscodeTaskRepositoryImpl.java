package com.calles.platform.transcode.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.transcode.domain.model.MediaCodec;
import com.calles.platform.transcode.domain.model.MediaFormat;
import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.domain.model.TranscodeTask;
import com.calles.platform.transcode.domain.model.TranscodeTaskStatus;
import com.calles.platform.transcode.domain.repository.TranscodeTaskRepository;
import com.calles.platform.transcode.infrastructure.persistence.entity.TranscodeTaskPO;
import com.calles.platform.transcode.infrastructure.persistence.mapper.TranscodeTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 转码工单领域仓储实现类 (TranscodeTaskRepositoryImpl)。
 *
 * <p>基于 MyBatis-Plus 实现领域对象与物理表 PO 的双向映射与持久化。</p>
 */
@Repository
@RequiredArgsConstructor
public class TranscodeTaskRepositoryImpl implements TranscodeTaskRepository {

    private final TranscodeTaskMapper mapper;

    @Override
    public int insert(TranscodeTask task) {
        TranscodeTaskPO po = toPO(task);
        return mapper.insert(po);
    }

    @Override
    public int updateById(TranscodeTask task) {
        TranscodeTaskPO po = toPO(task);
        return mapper.updateById(po);
    }

    @Override
    public Optional<TranscodeTask> findById(String id) {
        TranscodeTaskPO po = mapper.selectById(id);
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public Optional<TranscodeTask> findBySpec(String videoId, QualityPreset quality, MediaFormat format) {
        LambdaQueryWrapper<TranscodeTaskPO> query = new LambdaQueryWrapper<TranscodeTaskPO>()
                .eq(TranscodeTaskPO::getVideoId, videoId)
                .eq(TranscodeTaskPO::getTargetQuality, quality.getCode())
                .eq(TranscodeTaskPO::getTargetFormat, format.getValue());
        TranscodeTaskPO po = mapper.selectOne(query);
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public List<TranscodeTask> findByVideoId(String videoId) {
        LambdaQueryWrapper<TranscodeTaskPO> query = new LambdaQueryWrapper<TranscodeTaskPO>()
                .eq(TranscodeTaskPO::getVideoId, videoId)
                .orderByAsc(TranscodeTaskPO::getCreatedAt);
        return mapper.selectList(query).stream().map(this::toDomain).toList();
    }

    @Override
    public List<TranscodeTask> findStaleTasks(TranscodeTaskStatus status, LocalDateTime beforeTime, int limit) {
        LambdaQueryWrapper<TranscodeTaskPO> query = new LambdaQueryWrapper<TranscodeTaskPO>()
                .eq(TranscodeTaskPO::getStatus, status.getValue())
                .le(TranscodeTaskPO::getUpdatedAt, beforeTime)
                .last("LIMIT " + limit);
        return mapper.selectList(query).stream().map(this::toDomain).toList();
    }

    private TranscodeTaskPO toPO(TranscodeTask domain) {
        if (domain == null) return null;
        return TranscodeTaskPO.builder()
                .id(domain.getId())
                .videoId(domain.getVideoId())
                .authorId(domain.getAuthorId())
                .sourceFileId(domain.getSourceFileId())
                .targetQuality(domain.getTargetQuality() != null ? domain.getTargetQuality().getCode() : null)
                .targetFormat(domain.getTargetFormat() != null ? domain.getTargetFormat().getValue() : null)
                .targetCodec(domain.getTargetCodec() != null ? domain.getTargetCodec().getValue() : null)
                .status(domain.getStatus() != null ? domain.getStatus().getValue() : null)
                .retryCount(domain.getRetryCount())
                .maxRetries(domain.getMaxRetries())
                .outputFileId(domain.getOutputFileId())
                .outputFileSize(domain.getOutputFileSize())
                .outputBitrate(domain.getOutputBitrate())
                .outputFps(domain.getOutputFps())
                .outputWidth(domain.getOutputWidth())
                .outputHeight(domain.getOutputHeight())
                .videoDuration(domain.getVideoDuration())
                .errorMessage(domain.getErrorMessage())
                .transcodeCostMs(domain.getTranscodeCostMs())
                .totalCostMs(domain.getTotalCostMs())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    private TranscodeTask toDomain(TranscodeTaskPO po) {
        if (po == null) return null;
        return TranscodeTask.builder()
                .id(po.getId())
                .videoId(po.getVideoId())
                .authorId(po.getAuthorId())
                .sourceFileId(po.getSourceFileId())
                .targetQuality(po.getTargetQuality() != null ? QualityPreset.fromCode(po.getTargetQuality()) : null)
                .targetFormat(po.getTargetFormat() != null ? MediaFormat.valueOf(po.getTargetFormat()) : null)
                .targetCodec(po.getTargetCodec() != null ? MediaCodec.valueOf(po.getTargetCodec()) : null)
                .status(po.getStatus() != null ? TranscodeTaskStatus.valueOf(po.getStatus()) : null)
                .retryCount(po.getRetryCount() != null ? po.getRetryCount() : 0)
                .maxRetries(po.getMaxRetries() != null ? po.getMaxRetries() : 3)
                .outputFileId(po.getOutputFileId())
                .outputFileSize(po.getOutputFileSize())
                .outputBitrate(po.getOutputBitrate())
                .outputFps(po.getOutputFps())
                .outputWidth(po.getOutputWidth())
                .outputHeight(po.getOutputHeight())
                .videoDuration(po.getVideoDuration())
                .errorMessage(po.getErrorMessage())
                .transcodeCostMs(po.getTranscodeCostMs())
                .totalCostMs(po.getTotalCostMs())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}
