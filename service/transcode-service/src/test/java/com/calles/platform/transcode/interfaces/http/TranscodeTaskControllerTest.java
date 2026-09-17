package com.calles.platform.transcode.interfaces.http;

import com.calles.platform.transcode.application.service.TranscodeApplicationService;
import com.calles.platform.transcode.domain.model.MediaCodec;
import com.calles.platform.transcode.domain.model.MediaFormat;
import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.domain.model.TranscodeTask;
import com.calles.platform.transcode.domain.repository.TranscodeTaskRepository;
import com.calles.platform.transcode.interfaces.http.advice.TranscodeExceptionHandler;
import com.calles.platform.transcode.interfaces.http.dto.TranscodeRequests;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 视频转码工单 HTTP 控制器 (TranscodeTaskControllerTest) 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class TranscodeTaskControllerTest {

    @Mock private TranscodeTaskRepository taskRepository;
    @Mock private TranscodeApplicationService transcodeApplicationService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TranscodeTaskController controller = new TranscodeTaskController(taskRepository, transcodeApplicationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TranscodeExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("查询工单成功：工单存在时返回 200 及详细数据")
    void getTask_success() throws Exception {
        TranscodeTask task = TranscodeTask.create("video_1", "author_1", "source_1", QualityPreset.P720, MediaFormat.MP4, MediaCodec.H264);
        when(taskRepository.findById("task_100")).thenReturn(Optional.of(task));

        mockMvc.perform(get("/api/transcode/tasks/task_100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.videoId").value("video_1"))
                .andExpect(jsonPath("$.data.targetQuality").value("P720"));
    }

    @Test
    @DisplayName("查询工单未找到：工单不存在时由全局异常处理器捕获并返回 404")
    void getTask_notFound() throws Exception {
        when(taskRepository.findById("unknown_task")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/transcode/tasks/unknown_task"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("转码工单不存在: unknown_task"));
    }

    @Test
    @DisplayName("手动触发转码成功：请求参数完整时正常执行流水线并返回 200")
    void triggerTranscode_success() throws Exception {
        TranscodeRequests.TriggerTranscode request = new TranscodeRequests.TriggerTranscode(
                "video_2", "author_2", "source_2", "720P"
        );
        TranscodeTask task = TranscodeTask.create("video_2", "author_2", "source_2", QualityPreset.P720, MediaFormat.MP4, MediaCodec.H264);
        when(transcodeApplicationService.processTask(eq("video_2"), eq("author_2"), eq("source_2"), any(QualityPreset.class)))
                .thenReturn(task);

        mockMvc.perform(post("/api/transcode/tasks/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.videoId").value("video_2"));
    }

    @Test
    @DisplayName("手动触发转码失败：缺少必填字段时 Bean Validation 拦截并返回 400")
    void triggerTranscode_validationError() throws Exception {
        TranscodeRequests.TriggerTranscode request = new TranscodeRequests.TriggerTranscode(
                "", "", "", "720P"
        );

        mockMvc.perform(post("/api/transcode/tasks/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
