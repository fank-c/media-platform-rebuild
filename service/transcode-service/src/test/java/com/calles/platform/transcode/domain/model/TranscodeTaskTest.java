package com.calles.platform.transcode.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转码工单领域聚合根 (TranscodeTask) 状态机与生命周期单元测试。
 */
class TranscodeTaskTest {

    @Test
    @DisplayName("工厂方法初始化任务：处于 PENDING 初始状态且唯一 ID 就绪")
    void createTask_success() {
        TranscodeTask task = TranscodeTask.create(
                "video_1",
                "author_1",
                "file_source_1",
                QualityPreset.P720,
                MediaFormat.MP4,
                MediaCodec.H264
        );

        assertNotNull(task.getId());
        assertEquals("video_1", task.getVideoId());
        assertEquals(QualityPreset.P720, task.getTargetQuality());
        assertEquals(TranscodeTaskStatus.PENDING, task.getStatus());
        assertEquals(0, task.getRetryCount());
        assertFalse(task.getStatus().isTerminal());
    }

    @Test
    @DisplayName("全流程状态机正常流转：PENDING -> DOWNLOADING -> TRANSCODING -> UPLOADING -> NOTIFYING -> COMPLETED")
    void stateTransitions_success() {
        TranscodeTask task = TranscodeTask.create(
                "video_1", "author_1", "file_src",
                QualityPreset.P720, MediaFormat.MP4, MediaCodec.H264
        );

        // 步骤 1：下载中
        task.markDownloading();
        assertEquals(TranscodeTaskStatus.DOWNLOADING, task.getStatus());

        // 步骤 2：压制中
        task.markTranscoding();
        assertEquals(TranscodeTaskStatus.TRANSCODING, task.getStatus());

        // 步骤 3：上传中
        task.markUploading(1500L);
        assertEquals(TranscodeTaskStatus.UPLOADING, task.getStatus());
        assertEquals(1500L, task.getTranscodeCostMs());

        // 步骤 4：回调通知中
        task.markNotifying("file_output_1", 2048000L);
        assertEquals(TranscodeTaskStatus.NOTIFYING, task.getStatus());
        assertEquals("file_output_1", task.getOutputFileId());
        assertEquals(2048000L, task.getOutputFileSize());

        // 步骤 5：完成终态
        task.complete("file_output_1", 2048000L, 2500, 30, 1280, 720, 60, 3200L);
        assertEquals(TranscodeTaskStatus.COMPLETED, task.getStatus());
        assertTrue(task.getStatus().isTerminal());
        assertEquals(1280, task.getOutputWidth());
        assertEquals(720, task.getOutputHeight());
        assertEquals(60, task.getVideoDuration());
    }

    @Test
    @DisplayName("终态防护：已处于 COMPLETED 终态时禁止再次发生前向跃迁")
    void terminalState_rejectsTransition() {
        TranscodeTask task = TranscodeTask.create(
                "video_1", "author_1", "file_src",
                QualityPreset.P720, MediaFormat.MP4, MediaCodec.H264
        );
        task.complete("file_output_1", 2048000L, 2500, 30, 1280, 720, 60, 3000L);

        assertThrows(IllegalStateException.class, task::markDownloading);
        assertThrows(IllegalStateException.class, task::markTranscoding);
    }

    @Test
    @DisplayName("失败与重置自愈：fail 增加重试计数并在未超限时允许 resetForRetry")
    void failAndReset_success() {
        TranscodeTask task = TranscodeTask.create(
                "video_1", "author_1", "file_src",
                QualityPreset.P720, MediaFormat.MP4, MediaCodec.H264
        );
        task.setMaxRetries(2);

        // 第一次失败
        task.fail("FFmpeg error", 500L);
        assertEquals(TranscodeTaskStatus.FAILED, task.getStatus());
        assertEquals(1, task.getRetryCount());
        assertEquals("FFmpeg error", task.getErrorMessage());

        // 允许重置重试
        task.resetForRetry();
        assertEquals(TranscodeTaskStatus.PENDING, task.getStatus());

        // 第二次失败
        task.fail("Network timeout", 800L);
        assertEquals(2, task.getRetryCount());

        // 达到最大上限后禁止再次重试
        assertThrows(IllegalStateException.class, task::resetForRetry);
    }
}
