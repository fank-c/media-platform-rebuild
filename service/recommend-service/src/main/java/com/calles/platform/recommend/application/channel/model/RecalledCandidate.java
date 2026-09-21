package com.calles.platform.recommend.application.channel.model;

import com.calles.platform.recommend.domain.model.CandidateVideo;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 多路召回物料候选载荷 (RecalledCandidate)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务应用层召回阶段产物数据载荷；</li>
 *   <li><b>业务承载</b>：封装由具体通道（个性化、探索、热点、关注）初步命中筛选的候选视频实体；</li>
 *   <li><b>溯源与可解释性</b>：携带所属召回通道标识、通道内微观打分与最终面向客户端展示的可解释推荐理由。</li>
 * </ul>
 * </p>
 */
@Getter
@AllArgsConstructor
public class RecalledCandidate {

    /** 候选视频实体聚合根 (包含 vid、作者、双层标签、发布时间等物料元数据)。 */
    private final CandidateVideo candidate;

    /** 召回渠道来源标识编码 (PERSONALIZED, EXPLORE_SIMILAR, EXPLORE_RANDOM, TRENDING, FOLLOWING, COLD_START)。 */
    private final String channel;

    /** 渠道内专属算法计算打分 (通常规整在 0.0 ~ 1.5 之间，数值越高代表通道内匹配优先级越高)。 */
    private final double score;

    /** 面向客户端呈现的可解释推荐文案 (例如："偏好标签推荐"、"相似兴趣探索"、"全站热点")。 */
    private final String reason;
}
