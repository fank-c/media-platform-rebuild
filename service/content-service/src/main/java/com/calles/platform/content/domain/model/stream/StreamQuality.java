package com.calles.platform.content.domain.model.stream;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频流画质清晰度规格枚举。
 *
 * <p>定义视频分发支持的不同分辨率档位，支持从标清、高清到超高清 4K 以及未经转码的原始规格 RAW。</p>
 */
@Getter
@RequiredArgsConstructor
public enum StreamQuality {

    /** 360P 标清 (流畅)。 */
    P360("360P"),

    /** 480P 清晰。 */
    P480("480P"),

    /** 720P 高清。 */
    P720("720P"),

    /** 1080P 全高清 (1080P 30fps)。 */
    P1080("1080P"),

    /** 1080P 60帧全高清 (1080P 60fps 高帧率)。 */
    P1080_60("1080P_60"),

    /** 4K 超高清 (2160P)。 */
    P4K("4K"),

    /** RAW 上传原画质（未转码原始母带）。 */
    RAW("RAW");

    /**
     * 数据库存储与接口交互对应的枚举字面量值。
     */
    @EnumValue
    private final String value;

    /**
     * 根据字符串字面量解析对应的清晰度枚举，若未匹配则默认返回 {@link #RAW}。
     *
     * @param value 字符串画质值（如 "1080P" 或 "P1080"）
     * @return 对应的画质枚举
     */
    public static StreamQuality fromValue(String value) {
        if (value == null) {
            return RAW;
        }
        for (StreamQuality quality : values()) {
            if (quality.value.equalsIgnoreCase(value) || quality.name().equalsIgnoreCase(value)) {
                return quality;
            }
        }
        return RAW;
    }
}
