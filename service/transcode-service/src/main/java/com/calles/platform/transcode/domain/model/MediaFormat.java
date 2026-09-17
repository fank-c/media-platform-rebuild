package com.calles.platform.transcode.domain.model;

import lombok.Getter;

/**
 * 流媒体封装格式枚举。
 */
@Getter
public enum MediaFormat {
    MP4("MP4"),
    HLS("HLS"),
    DASH("DASH");

    private final String value;

    MediaFormat(String value) {
        this.value = value;
    }
}
