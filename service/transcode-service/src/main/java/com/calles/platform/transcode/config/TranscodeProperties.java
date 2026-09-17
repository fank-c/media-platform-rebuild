package com.calles.platform.transcode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 转码微服务运行基础设施配置属性 (TranscodeProperties)。
 */
@Data
@ConfigurationProperties(prefix = "transcode")
public class TranscodeProperties {

    /**
     * 转码引擎类型：ffmpeg（调用宿主机二进制）或 mock（脱网开发与自动化测试桩）。
     */
    private String engineType = "ffmpeg";

    /**
     * 系统 ffmpeg 可执行文件二进制路径（默认在 PATH 中查找）。
     */
    private String ffmpegPath = "ffmpeg";

    /**
     * 系统 ffprobe 探测工具可执行文件路径（默认在 PATH 中查找）。
     */
    private String ffprobePath = "ffprobe";

    /**
     * 本地最大并发转码任务数限制（低配置硬件保护，默认 1）。
     */
    private int maxConcurrentTasks = 1;

    /**
     * 本地临时工作目录路径（用于原片拉流缓存、转码切片暂存及清理）。
     */
    private String workDir = "/tmp/calles-transcode";

    /**
     * 单个转码任务最大超时时限（秒，默认 600 秒 = 10 分钟）。
     */
    private int taskTimeoutSeconds = 600;

    /**
     * 回调内容服务超时重试最大次数。
     */
    private int callbackMaxRetries = 5;
}
