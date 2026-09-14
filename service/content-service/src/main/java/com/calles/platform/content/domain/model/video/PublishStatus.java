package com.calles.platform.content.domain.model.video;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频内容的发布与审核生命周期流转状态枚举。
 *
 * <p>状态机流转规范：
 * <ul>
 *   <li>{@link #DRAFT}（草稿） -&gt; 提交审核 -&gt; {@link #AUDITING}（审核中）；</li>
 *   <li>{@link #AUDITING} -&gt; 审核通过 -&gt; {@link #PUBLISHED}（已发布）；</li>
 *   <li>{@link #AUDITING} -&gt; 审核驳回 -&gt; {@link #REJECTED}（已拒绝）；</li>
 *   <li>{@link #REJECTED} -&gt; 修改元数据重新提审 -&gt; {@link #AUDITING}；</li>
 *   <li>{@link #PUBLISHED} -&gt; 创作者主动下架 -&gt; {@link #OFFLINE}（已下架）。</li>
 * </ul>
 * </p>
 */
@Getter
@RequiredArgsConstructor
public enum PublishStatus {

    /** 草稿阶段，仅创作者自身可见与编辑。 */
    DRAFT("DRAFT"),

    /** 提交审核中，等待机审或人工审核流水线判定。 */
    AUDITING("AUDITING"),

    /** 审核通过并已公开发布，可面向全网播放。 */
    PUBLISHED("PUBLISHED"),

    /** 审核未通过，被打回草稿箱，附带驳回原因说明。 */
    REJECTED("REJECTED"),

    /** 创作者主动下架或自主隐藏。 */
    OFFLINE("OFFLINE");

    /** 数据库列存储与 API 交互对应的字符串字面量值。 */
    @EnumValue
    private final String value;
}
