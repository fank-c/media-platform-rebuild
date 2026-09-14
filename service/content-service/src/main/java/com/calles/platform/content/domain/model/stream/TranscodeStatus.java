package com.calles.platform.content.domain.model.stream;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频流转码异步处理状态枚举。
 */
@Getter
@RequiredArgsConstructor
public enum TranscodeStatus {

    /** 等待调度转码。 */
    PENDING("PENDING"),
    /** 转码中。 */
    PROCESSING("PROCESSING"),
    /** 转码完成并入库可用。 */
    COMPLETED("COMPLETED"),
    /** 转码失败。 */
    FAILED("FAILED");

    @EnumValue
    private final String value;
}
