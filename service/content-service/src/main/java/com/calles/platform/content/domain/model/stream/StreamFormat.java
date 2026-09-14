package com.calles.platform.content.domain.model.stream;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频流封装格式枚举。
 *
 * <p>定义视频分发流支持的容器/流媒体协议格式，涵盖点播整片与切片自适应流。</p>
 */
@Getter
@RequiredArgsConstructor
public enum StreamFormat {

    /**
     * MP4 容器封装：通用性强，支持 Progressive Download（渐进式下载播放）。
     */
    MP4("MP4"),

    /**
     * HTTP Live Streaming (HLS / m3u8)：苹果主导的基于切片的自适应流媒体协议，移动端支持极佳。
     */
    HLS("HLS"),

    /**
     * Dynamic Adaptive Streaming over HTTP (DASH / mpd)：国际标准的自适应多码率切片流媒体协议。
     */
    DASH("DASH");

    /**
     * 数据库存储与接口交互对应的枚举字面量值。
     */
    @EnumValue
    private final String value;
}
