package com.calles.platform.content.domain.model.video;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频内容的发布与审核生命周期流转状态。
 */
@Getter
@RequiredArgsConstructor
public enum PublishStatus {

    /** 草稿阶段，仅创作者自身可见与编辑。 */
    DRAFT("DRAFT"),
    /** 提交审核中，等待机审或人工审核。 */
    AUDITING("AUDITING"),
    /** 审核通过并已公开发布。 */
    PUBLISHED("PUBLISHED"),
    /** 审核未通过，被打回草稿箱，附带 rejectReason。 */
    REJECTED("REJECTED"),
    /** 创作者主动下架或作者自主隐藏。 */
    OFFLINE("OFFLINE");

    @EnumValue
    private final String value;
}
