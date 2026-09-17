package com.calles.platform.transcode.infrastructure.engine;

import com.calles.platform.transcode.config.TranscodeProperties;
import com.calles.platform.transcode.domain.engine.TranscodeResult;
import com.calles.platform.transcode.domain.model.QualityPreset;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宿主机原生 FFmpeg/FFprobe 转码压制引擎 (FfmpegTranscodeEngine) 物理集成单元测试。
 */
class FfmpegTranscodeEngineTest {

    private TranscodeProperties properties;
    private FfmpegTranscodeEngine engine;
    private boolean ffmpegAvailable;

    @BeforeEach
    void checkEnvironment() {
        properties = new TranscodeProperties();
        properties.setFfmpegPath("ffmpeg");
        properties.setFfprobePath("ffprobe");
        properties.setTaskTimeoutSeconds(30);

        engine = new FfmpegTranscodeEngine(properties);

        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            ffmpegAvailable = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            ffmpegAvailable = false;
        }
    }

    @Test
    @DisplayName("系统级真实 FFmpeg 转码测试：将合成短视频压制为 720P MP4 切片并探测元数据")
    void transcode_realFfmpeg_success(@TempDir File tempDir) throws Exception {
        Assumptions.assumeTrue(ffmpegAvailable, "宿主机未检测到 ffmpeg，跳过物理转码测试");

        File sourceVideo = new File(tempDir, "source_1s.mp4");
        File workDir = new File(tempDir, "work");
        assertTrue(workDir.mkdirs());

        // 步骤 1：利用 ffmpeg testsrc 与 sine 滤镜合成 1 秒的合法 MP4 原始测试片源
        Process genProcess = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=1:size=640x360:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=1000:duration=1",
                "-c:v", "libx264", "-c:a", "aac",
                sourceVideo.getAbsolutePath()
        ).start();

        assertTrue(genProcess.waitFor(10, TimeUnit.SECONDS), "合成测试片源超时");
        assertEquals(0, genProcess.exitValue(), "合成测试片源失败");
        assertTrue(sourceVideo.exists() && sourceVideo.length() > 0);

        // 步骤 2：驱动转码引擎压制为 720P MP4 切片
        TranscodeResult result = engine.transcode(sourceVideo, QualityPreset.P720, workDir);

        // 步骤 3：核验产物规格与探测指标
        assertNotNull(result);
        assertNotNull(result.getOutputFile());
        assertTrue(result.getOutputFile().exists());
        assertTrue(result.getFileSize() > 0);
        assertEquals(1280, result.getWidth());
        assertEquals(720, result.getHeight());
        assertTrue(result.getBitrate() > 0);
        assertTrue(result.getDuration() >= 1);
        assertEquals("FFMPEG", engine.engineType());
    }
}
