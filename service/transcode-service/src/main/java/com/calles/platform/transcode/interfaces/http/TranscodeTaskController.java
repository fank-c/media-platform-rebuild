package com.calles.platform.transcode.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.transcode.application.service.TranscodeApplicationService;
import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.domain.model.TranscodeTask;
import com.calles.platform.transcode.domain.repository.TranscodeTaskRepository;
import com.calles.platform.transcode.exception.TranscodeException;
import com.calles.platform.transcode.interfaces.http.dto.TranscodeRequests;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频转码工单查询与调试管理 HTTP 控制器 (TranscodeTaskController)。
 *
 * <p>职责与边界：
 * <ul>
 *   <li><b>查询工单</b>：按工单 ID 获取转码任务实时流转状态与产物指标；</li>
 *   <li><b>手动触发</b>：供管理端或内部调试工具手动发起画质切片压制。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/transcode/tasks")
@RequiredArgsConstructor
public class TranscodeTaskController {

    private final TranscodeTaskRepository taskRepository;
    private final TranscodeApplicationService transcodeApplicationService;

    /**
     * 按工单 ID 查询转码任务详情。
     */
    @GetMapping("/{id}")
    public ApiResponse<TranscodeTask> getTask(@PathVariable String id) {
        TranscodeTask task = taskRepository.findById(id)
                .orElseThrow(() -> new TranscodeException(HttpStatus.NOT_FOUND, "转码工单不存在: " + id));
        return ApiResponse.ok(task);
    }

    /**
     * 手动触发指定视频清晰度的转码流水线。
     */
    @PostMapping("/trigger")
    public ApiResponse<TranscodeTask> triggerTranscode(@Valid @RequestBody TranscodeRequests.TriggerTranscode request) {
        QualityPreset preset;
        try {
            preset = request.quality() != null && !request.quality().isBlank()
                    ? QualityPreset.fromCode(request.quality())
                    : QualityPreset.P720;
        } catch (IllegalArgumentException e) {
            preset = QualityPreset.P720;
        }

        TranscodeTask task = transcodeApplicationService.processTask(
                request.videoId(),
                request.authorId(),
                request.sourceFileId(),
                preset
        );
        return ApiResponse.ok(task);
    }
}
