package com.calles.platform.transcode.domain.model;

import lombok.Getter;

/**
 * 视频压缩编码格式枚举。
 */
@Getter
public enum MediaCodec {
    H264("H264"),
    H265("H265"),
    AV1("AV1");

    private final String value;

    MediaCodec(String value) {
        this.value = value;
    }
}
