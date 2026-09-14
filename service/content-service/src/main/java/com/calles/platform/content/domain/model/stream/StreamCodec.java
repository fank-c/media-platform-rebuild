package com.calles.platform.content.domain.model.stream;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频流编码标准枚举。
 *
 * <p>定义视频转码流支持的视频压缩编码格式，不同编码在压缩率、播放兼容性与硬件解码支持上存在差异。</p>
 */
@Getter
@RequiredArgsConstructor
public enum StreamCodec {

    /**
     * H.264 / AVC 编码：兼容性最广，全平台设备与浏览器均原生支持。
     */
    H264("H264"),

    /**
     * H.265 / HEVC 编码：高压缩率，同等画质下比 H.264 节省约 40%-50% 码率，适合 1080P/4K 高清播放。
     */
    H265("H265"),

    /**
     * AV1 开源下一代编码：免专利费，压缩率极高，适合前沿移动端与 Web 端播放。
     */
    AV1("AV1");

    /**
     * 数据库存储与接口交互对应的枚举字面量值。
     */
    @EnumValue
    private final String value;
}
