package com.calles.platform.transcode.domain.engine;

import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.exception.TranscodeException;

import java.io.File;

/**
 * 视频转码引擎顶级抽象契约 (TranscodeEngine)。
 *
 * <p>屏蔽 FFmpeg、云转码服务或本地 Mock 桩等不同执行器的实现细节。</p>
 */
public interface TranscodeEngine {

    /**
     * 将源文件转码压制为目标清晰度预设的流媒体切片。
     *
     * @param sourceFile 本地原始视频物理文件
     * @param preset 目标清晰度规格预设 (如 720P, 1080P)
     * @param workDir 本次任务专用的独立临时工作目录
     * @return 包含产物物理文件句柄与探测元数据的转码结果
     * @throws TranscodeException 当转码或元数据探测失败时抛出
     */
    TranscodeResult transcode(File sourceFile, QualityPreset preset, File workDir) throws TranscodeException;

    /**
     * 引擎类型标识名称（如 "FFMPEG", "MOCK"）。
     */
    String engineType();
}
