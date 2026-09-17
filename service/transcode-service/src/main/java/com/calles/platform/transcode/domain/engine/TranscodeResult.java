package com.calles.platform.transcode.domain.engine;

import lombok.Builder;
import lombok.Getter;

import java.io.File;

/**
 * 转码引擎执行输出结果传输对象 (TranscodeResult)。
 */
@Getter
@Builder
public class TranscodeResult {

    /** 转码生成的切片物理文件句柄。 */
    private final File outputFile;

    /** 产物实际文件大小（字节）。 */
    private final long fileSize;

    /** 视频实际比特率 (kbps)。 */
    private final int bitrate;

    /** 视频实际帧率 (fps)。 */
    private final int fps;

    /** 实际像素宽度。 */
    private final int width;

    /** 实际像素高度。 */
    private final int height;

    /** 视频媒体精确时长（秒）。 */
    private final int duration;

    /** 转码纯计算执行耗时（毫秒）。 */
    private final long transcodeCostMs;
}
