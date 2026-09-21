package com.calles.platform.recommend.application.channel.impl;

import com.calles.platform.recommend.application.channel.RecommendRecallChannel;
import com.calles.platform.recommend.infrastructure.qdrant.dto.QdrantDTOs.ScoredPoint;
import lombok.extern.slf4j.Slf4j;

/**
 * 推荐召回通道通用抽象基类 (AbstractRecallChannel)。
 *
 * <p>职责与复用能力：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务应用层召回通道基础设施抽象；</li>
 *   <li><b>公共逻辑下沉</b>：封装 Qdrant 检索打分点提取视频短码、逗号分隔标签解析、基础异常日志与兜底防御；</li>
 *   <li><b>模板方法模式</b>：派生类仅需聚焦自身特色通道的召回源过滤、打分权重度量与可解释性文案组装。</li>
 * </ul>
 * </p>
 */
@Slf4j
public abstract class AbstractRecallChannel implements RecommendRecallChannel {

    /**
     * 从 Qdrant 检索命中的打分点实体中安全提取视频业务短码 (vid)。
     *
     * @param point Qdrant 打分点对象
     * @return 业务短码 vid，若 payload 中未包含则降级使用 point id，点为空时返回 null
     */
    protected String extractVidFromPoint(ScoredPoint point) {
        if (point == null) {
            return null;
        }
        if (point.payload() != null && point.payload().get("vid") != null) {
            return point.payload().get("vid").toString();
        }
        return point.id();
    }

    /**
     * 提取逗号分隔的标签字符串中的首个主要标签标识（如粗粒度领域标签）。
     *
     * @param tags 逗号分隔的标签 ID 字符串 (形如 "tag_tech,tag_ai")
     * @return 首要标签 ID，若输入为空或空白字符串时返回 null
     */
    protected String extractPrimaryTag(String tags) {
        if (tags == null || tags.isBlank()) {
            return null;
        }
        String[] parts = tags.split(",");
        return parts.length > 0 ? parts[0].trim() : null;
    }
}
