package com.calles.platform.content.domain.model.video;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频内容的公开可见性范围。
 */
@Getter
@RequiredArgsConstructor
public enum ContentVisibility {

    /** 完全公开。 */
    PUBLIC("PUBLIC"),
    /** 仅创作者自己可见。 */
    PRIVATE("PRIVATE"),
    /** 仅持有链接者可见。 */
    UNLISTED("UNLISTED");

    @EnumValue
    private final String value;
}
