package com.calles.platform.content.domain.model.stream;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频流转码异步处理生命周期流转状态枚举。
 *
 * <p>职责与状态机规范：
 * <ul>
 *   <li><b>所属边界</b>：转码切片规格层级的处理生命周期；</li>
 *   <li><b>状态跃迁</b>：
 *     <ul>
 *       <li>{@link #PENDING}：等待调度转码；</li>
 *       <li>{@link #PROCESSING}：转码任务正在计算运行；</li>
 *       <li>{@link #COMPLETED}：转码产物已上传且文件记录就绪，可供前台播放；</li>
 *       <li>{@link #FAILED}：转码异常或失败（如视频损坏、编码器不支持）。</li>
 *     </ul>
 *   </li>
 * </ul>
 * </p>
 */
@Getter
@RequiredArgsConstructor
public enum TranscodeStatus {

    /** 等待任务调度分配计算资源。 */
    PENDING("PENDING"),

    /** 转码切片渲染计算中。 */
    PROCESSING("PROCESSING"),

    /** 转码成功完成，切片已入库并处于可播放状态。 */
    COMPLETED("COMPLETED"),

    /** 转码由于参数异常或媒体流损坏导致处理失败。 */
    FAILED("FAILED");

    /** 数据库列存储与 API 交互对应的字符串字面量值。 */
    @EnumValue
    private final String value;
}
