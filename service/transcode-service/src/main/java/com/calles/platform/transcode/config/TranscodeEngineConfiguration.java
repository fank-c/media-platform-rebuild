package com.calles.platform.transcode.config;

import com.calles.platform.transcode.domain.engine.TranscodeEngine;
import com.calles.platform.transcode.infrastructure.engine.FfmpegTranscodeEngine;
import com.calles.platform.transcode.infrastructure.engine.MockTranscodeEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 视频转码引擎条件自动化装配配置类 (TranscodeEngineConfiguration)。
 *
 * <p>根据 {@code transcode.engine-type} 配置项切换注入真实 FFmpeg 物理引擎或 Mock 模拟测试桩。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(TranscodeProperties.class)
public class TranscodeEngineConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "transcode", name = "engine-type", havingValue = "mock")
    public TranscodeEngine mockTranscodeEngine() {
        log.info("装配视频转码模拟引擎 [MockTranscodeEngine]");
        return new MockTranscodeEngine();
    }

    @Bean
    @ConditionalOnProperty(prefix = "transcode", name = "engine-type", havingValue = "ffmpeg", matchIfMissing = true)
    public TranscodeEngine ffmpegTranscodeEngine(TranscodeProperties properties) {
        log.info("装配系统原生视频转码引擎 [FfmpegTranscodeEngine], ffmpegPath={}, ffprobePath={}",
                properties.getFfmpegPath(), properties.getFfprobePath());
        return new FfmpegTranscodeEngine(properties);
    }
}
