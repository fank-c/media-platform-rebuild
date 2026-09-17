package com.calles.platform.transcode.infrastructure.engine;

import com.calles.platform.transcode.domain.engine.TranscodeResult;
import com.calles.platform.transcode.domain.model.QualityPreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视频转码模拟引擎 (MockTranscodeEngine) 单元测试。
 */
class MockTranscodeEngineTest {

    private final MockTranscodeEngine engine = new MockTranscodeEngine();

    @Test
    @DisplayName("转码测试：成功生成 720P 规格切片并输出正确媒体指标")
    void transcode_p720_success(@TempDir File tempDir) throws IOException {
        // 准备一个源文件
        File sourceFile = new File(tempDir, "source.mp4");
        try (FileOutputStream fos = new FileOutputStream(sourceFile)) {
            fos.write("dummy-source-video-binary-content".getBytes());
        }

        File workDir = new File(tempDir, "task_work");
        assertTrue(workDir.mkdirs());

        TranscodeResult result = engine.transcode(sourceFile, QualityPreset.P720, workDir);

        assertNotNull(result);
        assertNotNull(result.getOutputFile());
        assertTrue(result.getOutputFile().exists());
        assertEquals(sourceFile.length(), result.getFileSize());
        assertEquals(1280, result.getWidth());
        assertEquals(720, result.getHeight());
        assertEquals(QualityPreset.P720.getTargetBitrate(), result.getBitrate());
        assertEquals(QualityPreset.P720.getTargetFps(), result.getFps());
        assertEquals(120, result.getDuration());
        assertTrue(result.getTranscodeCostMs() >= 10);
        assertEquals("MOCK", engine.engineType());
    }
}
