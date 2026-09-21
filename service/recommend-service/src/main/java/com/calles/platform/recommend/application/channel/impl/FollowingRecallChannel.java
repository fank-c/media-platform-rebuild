package com.calles.platform.recommend.application.channel.impl;

import com.calles.platform.recommend.application.channel.RecommendRecallChannel;
import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.channel.model.RecalledCandidate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 创作者关注流推荐通道 (FollowingRecallChannel)。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>目标配比</b>：占据单次推送的 10% 配额；</li>
 *   <li><b>核心定位</b>：促成私域互动回流，强化用户对心仪创作者的长期黏性；</li>
 *   <li><b>演进路线</b>：
 *     1. 阶段一（当前）：通道预留与优雅缺省。由于 {@code user-service} 关注模块（{@code user_follow}）尚未迁移完成，
 *        当前安全返回空列表，其 10% 配额由外层槽位混合编排器自动溢出回补至核心个性化池；
 *     2. 阶段二（未来）：待关注服务就绪后，通过 OpenFeign 调用拉取关注作者新作并组装返回。
 *   </li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowingRecallChannel implements RecommendRecallChannel {

    /** 通道唯一业务标识。 */
    public static final String CHANNEL_NAME = "FOLLOWING";

    /** 该通道在多路推荐混合流中的基准目标配比 (10%)。 */
    private static final int TARGET_PERCENTAGE = 10;

    @Override
    public String getChannelName() {
        return CHANNEL_NAME;
    }

    @Override
    public int getTargetRatioPercentage() {
        return TARGET_PERCENTAGE;
    }

    @Override
    public boolean supports(RecallContext context) {
        return context != null && context.isLogin();
    }

    /**
     * 执行关注流召回。
     *
     * <p>阶段一实现策略：
     * 由于用户微服务关注关系模块尚未完成拆分迁移，当前采取非阻断的优雅缺省策略返回空列表。
     * 外层混合槽位编排器检测到配额缺损时，会自动将其 10% 物理槽位向上回补给核心个性化利用池，保障单屏推荐卡片数量饱和。</p>
     *
     * @param context 统一召回上下文
     * @param count 期望召回上限
     * @return 召回候选物料列表 (阶段一始终安全返回空列表)
     */
    @Override
    public List<RecalledCandidate> recall(RecallContext context, int count) {
        // 步骤 1：阶段一优雅缺省降级，未接入用户微服务关注流前安全返回空列表
        log.debug("关注推荐通道触发召回 (当前阶段一缺省降级为空，配额由核心个性化池吸收): userId={}, count={}", context.getUserId(), count);
        return Collections.emptyList();
    }
}
