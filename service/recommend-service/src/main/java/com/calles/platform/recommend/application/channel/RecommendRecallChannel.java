package com.calles.platform.recommend.application.channel;

import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.channel.model.RecalledCandidate;
import com.calles.platform.recommend.application.service.RecommendFeedApplicationService;

import java.util.List;

/**
 * 多路推荐召回通道契约接口 (RecommendRecallChannel)。
 *
 * <p>职责与协作边界：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务应用层策略抽象契约；</li>
 *   <li><b>标准行为规范</b>：统一抽象平台各异构召回源（核心个性化利用、探索发现破圈、全站高热爆款、关注私域互动）的行为契约；</li>
 *   <li><b>协同机制</b>：由 {@link RecommendFeedApplicationService}
 *       统一调度，基于基准配比并行召回候选池物料，支撑后续槽位交织打散与多样性重排。</li>
 * </ul>
 * </p>
 */
public interface RecommendRecallChannel {

    /**
     * 获取通道全局唯一标识名称。
     *
     * @return 业务通道标识 (如 PERSONALIZED, EXPLORE, TRENDING, FOLLOWING)
     */
    String getChannelName();

    /**
     * 获取该通道在混合流中的基准目标配比百分比 (如 50 代表 50%)。
     *
     * @return 配比整数百分比 (范围 0~100)
     */
    int getTargetRatioPercentage();

    /**
     * 执行指定数量的候选物料召回。
     *
     * @param context 统一召回上下文 (包含用户画像、已屏蔽黑名单、请求容量等)
     * @param count 本次期望召回的目标候选物料条数上限 (大于 0)
     * @return 召回候选物料列表 (按通道内专属度量打分降序排列，空列表代表无匹配候选)
     */
    List<RecalledCandidate> recall(RecallContext context, int count);
}
