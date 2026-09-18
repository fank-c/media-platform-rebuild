package com.calles.platform.transcode.domain.model;

import lombok.Getter;

import java.util.Arrays;

/**
 * 视频转码清晰度规格预设枚举 (QualityPreset)。
 *
 * <p>职责说明：
 * <ul>
 *   <li>定义目标切片标准物理分辨率、默认视频码率及音频码率约束；</li>
 *   <li>与下游内容服务 (content-service) 的 StreamQuality 及任务类型 TaskType 保持语义兼容。</li>
 * </ul>
 * </p>
 */
@Getter
public enum QualityPreset {

    P720("720P", 1280, 720, 2500, 128, "720P高清"),
    P1080("1080P", 1920, 1080, 4500, 192, "1080P超清"),
    P4K("4K", 3840, 2160, 15000, 256, "4K超高清");

    /** 业务标准规格编码（如 720P, 1080P, 4K）。 */
    private final String code;

    /** 目标像素宽度。 */
    private final int targetWidth;

    /** 目标像素高度。 */
    private final int targetHeight;

    /** 默认视频压制比特率 (kbps)。 */
    private final int defaultBitrateKbps;

    /** 默认音频压制比特率 (kbps)。 */
    private final int audioBitrateKbps;

    /** 规格中文描述。 */
    private final String description;

    QualityPreset(String code, int targetWidth, int targetHeight, int defaultBitrateKbps, int audioBitrateKbps, String description) {
        this.code = code;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.defaultBitrateKbps = defaultBitrateKbps;
        this.audioBitrateKbps = audioBitrateKbps;
        this.description = description;
    }

    /**
     * 目标压制视频比特率 (kbps)。
     */
    public int getTargetBitrate() {
        return defaultBitrateKbps;
    }

    /**
     * 目标压制帧率 (fps)。
     */
    public int getTargetFps() {
        return this == P4K ? 60 : 30;
    }

    /**
     * 转换为对齐 content-service TaskType 契约的标准流水线任务类型编码。
     *
     * <p>例如：P720 对应 "TRANSCODE_720P"，P1080 对应 "TRANSCODE_1080P"，P4K 对应 "TRANSCODE_4K"。</p>
     *
     * @return 业务标准子任务类型字符串 (如 "TRANSCODE_720P")
     */
    public String toTaskType() {
        return "TRANSCODE_" + code;
    }

    /**
     * 根据字符串编码模糊匹配预设规格（忽略大小写与空格）。
     *
     * @param value 输入规格字符串 (如 "720p", "P720", "1080P")
     * @return 对应的规格枚举
     * @throws IllegalArgumentException 未知规格时抛出
     */
    public static QualityPreset fromCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("转码规格不能为空");
        }
        String clean = value.trim().toUpperCase().replace("P", "");
        return switch (clean) {
            case "720" -> P720;
            case "1080" -> P1080;
            case "4K", "2160" -> P4K;
            default -> Arrays.stream(values())
                    .filter(p -> p.code.equalsIgnoreCase(value.trim()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("不支持的转码清晰度规格: " + value));
        };
    }
}
