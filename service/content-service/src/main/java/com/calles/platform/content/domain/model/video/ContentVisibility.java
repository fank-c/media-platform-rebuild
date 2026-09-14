package com.calles.platform.content.domain.model.video;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频内容的公开可见性范围枚举。
 *
 * <p>职责与可见性策略：
 * <ul>
 *   <li><b>所属边界</b>：控制视频在推荐、搜索及播放流消费端的数据暴露范围；</li>
 *   <li><b>取值含义</b>：
 *     <ul>
 *       <li>{@link #PUBLIC}：完全公开，进入公共信息流与搜索推荐池；</li>
 *       <li>{@link #PRIVATE}：仅创作者本人及平台超级管理员可见，不进入公域；</li>
 *       <li>{@link #UNLISTED}：仅持有视频链接或 vid 者可见，不出现在推荐流与检索列表中。</li>
 *     </ul>
 *   </li>
 * </ul>
 * </p>
 */
@Getter
@RequiredArgsConstructor
public enum ContentVisibility {

    /** 完全公开，所有人均可搜索并查看。 */
    PUBLIC("PUBLIC"),

    /** 私密内容，仅创作者自身与管理员可访问。 */
    PRIVATE("PRIVATE"),

    /** 仅凭公开链接可见，不展示在推荐首页与搜索结果中。 */
    UNLISTED("UNLISTED");

    /** 数据库列存储与 API 交互对应的字符串字面量值。 */
    @EnumValue
    private final String value;
}
