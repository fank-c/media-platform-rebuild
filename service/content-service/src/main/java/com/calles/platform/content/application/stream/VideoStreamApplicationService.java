package com.calles.platform.content.application.stream;

import com.calles.platform.content.domain.model.stream.StreamCodec;
import com.calles.platform.content.domain.model.stream.StreamFormat;
import com.calles.platform.content.domain.model.stream.StreamQuality;
import com.calles.platform.content.domain.model.stream.TranscodeStatus;
import com.calles.platform.content.domain.model.stream.VideoStream;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.domain.repository.VideoStreamRepository;
import com.calles.platform.content.exception.ContentException;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频转码流资产注册与维护应用服务。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：负责视频转码切片媒体资产的规格归档、多清晰度切片版本管理与状态同步；</li>
 *   <li><b>协作对象</b>：协同 {@link VideoStreamRepository} 与 {@link VideoContentRepository}；</li>
 *   <li><b>幂等性设计</b>：接收异步转码微服务的回调，同一 (videoId, quality, format) 规格流记录重复到达时执行更新覆盖而非重复插入。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoStreamApplicationService {

    /** 视频转码流仓储。 */
    private final VideoStreamRepository videoStreamRepository;

    /** 视频聚合根仓储，用于校验视频归属主体。 */
    private final VideoContentRepository videoContentRepository;

    /** 视频异步流水线任务协调器。 */
    private final com.calles.platform.content.application.task.VideoTaskCoordinator videoTaskCoordinator;

    /**
     * 注册或更新转码流媒体切片记录（幂等安全）。
     *
     * @param request 转码微服务回调参数传输对象
     * @throws ContentException 当找不到关联的主视频记录时抛出 404 NOT_FOUND
     */
    @Transactional
    public void registerStream(VideoRequests.TranscodeCallback request) {
        // 步骤 1：校验转码产物对应的主视频实体是否存在
        videoContentRepository.findById(request.videoId())
                .orElseThrow(() -> new ContentException(HttpStatus.NOT_FOUND, "未找到关联视频: " + request.videoId()));

        // 步骤 2：解析规格参数，提供标准默认回退（默认 MP4 格式、H264 编码与 COMPLETED 状态）
        StreamQuality quality = StreamQuality.fromValue(request.quality());
        StreamFormat format = request.format() != null ? StreamFormat.valueOf(request.format().trim().toUpperCase()) : StreamFormat.MP4;
        StreamCodec codec = request.codec() != null ? StreamCodec.valueOf(request.codec().trim().toUpperCase()) : StreamCodec.H264;
        TranscodeStatus transcodeStatus = request.transcodeStatus() != null
                ? TranscodeStatus.valueOf(request.transcodeStatus().trim().toUpperCase())
                : TranscodeStatus.COMPLETED;

        // 步骤 3：根据 (videoId, quality, format) 复合唯一键探测是否存在既有记录
        Optional<VideoStream> existing = videoStreamRepository.findBySpec(request.videoId(), quality, format);
        if (existing.isPresent()) {
            // 步骤 4A：存在既有记录，更新文件资产 ID、码率、帧率与最终转码状态（支持转码重试覆盖）
            VideoStream stream = existing.get();
            stream.setFileId(request.fileId());
            stream.setFileSize(request.fileSize() != null ? request.fileSize() : stream.getFileSize());
            stream.setBitrate(request.bitrate());
            stream.setFps(request.fps());
            stream.setTranscodeStatus(transcodeStatus);
            log.info("视频 [{}] 已存在规格为 [{} - {}] 的流记录，更新状态", request.videoId(), quality, format);
        } else {
            // 步骤 4B：全新规格切片，构建流媒体实体并持久化入库
            VideoStream stream = VideoStream.builder()
                    .id(UUID.randomUUID().toString().replace("-", ""))
                    .videoId(request.videoId())
                    .quality(quality)
                    .format(format)
                    .codec(codec)
                    .fileId(request.fileId())
                    .fileSize(request.fileSize() != null ? request.fileSize() : 0L)
                    .bitrate(request.bitrate())
                    .fps(request.fps())
                    .transcodeStatus(transcodeStatus)
                    .createdAt(LocalDateTime.now())
                    .build();
            videoStreamRepository.insert(stream);
            log.info("成功注册视频 [{}] 的新流媒体切片: [{} - {}], fileId=[{}]", request.videoId(), quality, format, request.fileId());
        }

        // 步骤 5：同步驱动流水线转码子任务状态跃迁与发布门禁评估
        TaskType mappedTaskType = switch (quality) {
            case P720 -> TaskType.TRANSCODE_720P;
            case P1080, P1080_60 -> TaskType.TRANSCODE_1080P;
            case P4K -> TaskType.TRANSCODE_4K;
            default -> null;
        };

        if (mappedTaskType != null) {
            if (transcodeStatus == TranscodeStatus.COMPLETED) {
                videoTaskCoordinator.completeTask(request.videoId(), mappedTaskType);
            } else if (transcodeStatus == TranscodeStatus.FAILED) {
                videoTaskCoordinator.failTask(request.videoId(), mappedTaskType, "转码切片处理失败");
            } else if (transcodeStatus == TranscodeStatus.PROCESSING) {
                videoTaskCoordinator.startTask(request.videoId(), mappedTaskType);
            }
        }
    }
}

