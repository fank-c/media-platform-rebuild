package com.calles.platform.transcode.domain.model;

import lombok.Getter;

/**
 * 转码子任务流转生命周期状态机枚举 (TranscodeTaskStatus)。
 *
 * <p>状态流转顺序：
 * PENDING (已排队) -> DOWNLOADING (源片下载中) -> TRANSCODING (转码压制中) ->
 * UPLOADING (切片上传中) -> NOTIFYING (门禁回调中) -> COMPLETED (终局成功) 或 FAILED (终局失败)
 * </p>
 */
@Getter
public enum TranscodeTaskStatus {

    /** 任务已创建排队中。 */
    PENDING("PENDING", "排队中"),

    /** 正在从对象存储拉取源视频。 */
    DOWNLOADING("DOWNLOADING", "源片下载中"),

    /** FFmpeg 转码执行中。 */
    TRANSCODING("TRANSCODING", "转码压制中"),

    /** 正在将转码切片上传至文件服务。 */
    UPLOADING("UPLOADING", "产物上传中"),

    /** 正在回调内容微服务更新流资产并驱动发布门禁。 */
    NOTIFYING("NOTIFYING", "门禁回调中"),

    /** 终局状态：转码与全链路处理成功完成。 */
    COMPLETED("COMPLETED", "已完成"),

    /** 终局状态：任务处理失败。 */
    FAILED("FAILED", "处理失败");

    private final String value;
    private final String description;

    TranscodeTaskStatus(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 判断当前状态是否为终局状态（已完结不可再流转）。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
