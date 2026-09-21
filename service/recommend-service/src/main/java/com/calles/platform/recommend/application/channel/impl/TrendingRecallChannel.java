package com.calles.platform.recommend.application.channel.impl;

import com.calles.platform.recommend.application.channel.RecommendRecallChannel;
import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.channel.model.RecalledCandidate;

import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.FeedbackLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 近期高热度全站爆款推荐通道 (TrendingRecallChannel)。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>目标配比</b>：占据单次推送的 10% 配额；</li>
 *   <li><b>核心定位</b>：提供全站社会认同（Social Proof）与近期爆款话题共鸣；</li>
 *   <li><b>阶段演进</b>：
 *     1. 阶段一（当前）：从 {@code feedbackLogRepository} 近 24 小时高频有效播放日志中统计 Top 物料；
 *     2. 兜底回退：若行为事实不足，自动回退最新优质发布物料池；
 *     3. 阶段二（未来）：接入 {@code interaction-service} 点赞/播放热榜。
 *   </li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendingRecallChannel implements RecommendRecallChannel {

    /** 通道唯一业务标识。 */
    public static final String CHANNEL_NAME = "TRENDING";

    /** 该通道在多路推荐混合流中的基准目标配比 (10%)。 */
    private static final int TARGET_PERCENTAGE = 10;

    /** 行为反馈日志持久化仓储 (用于聚合统计近 24 小时播放热度流水)。 */
    private final FeedbackLogRepository feedbackLogRepository;

    /** 推荐候选视频物料仓储。 */
    private final CandidateVideoRepository candidateVideoRepository;

    @Override
    public String getChannelName() {
        return CHANNEL_NAME;
    }

    @Override
    public int getTargetRatioPercentage() {
        return TARGET_PERCENTAGE;
    }

    @Override
    public List<RecalledCandidate> recall(RecallContext context, int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }

        List<RecalledCandidate> result = new ArrayList<>();

        // 步骤 1：查询近 24 小时播放次数最多的物料短码列表
        LocalDateTime since = LocalDateTime.now().minusDays(1);
        List<String> hotVids = Collections.emptyList();
        try {
            hotVids = feedbackLogRepository.findTopVidsByPlays(since, count * 3);
        } catch (Exception ex) {
            log.warn("查询近期高热度流水异常，降级候选池: error={}", ex.getMessage());
        }

        if (hotVids != null && !hotVids.isEmpty()) {
            List<CandidateVideo> candidates = candidateVideoRepository.findByVids(hotVids);
            for (CandidateVideo cv : candidates) {
                if (result.size() >= count) {
                    break;
                }
                if (cv.isRecommendable()) {
                    result.add(new RecalledCandidate(cv, CHANNEL_NAME, 0.9, "全站热点"));
                }
            }
        }

        // 步骤 2：若热度物料不足，使用候选池最新发布活跃视频作为热度兜底
        if (result.size() < count) {
            List<CandidateVideo> recent = candidateVideoRepository.findRecentActive(count * 2);
            for (CandidateVideo cv : recent) {
                if (result.size() >= count) {
                    break;
                }
                boolean exists = result.stream().anyMatch(r -> r.getCandidate().getVid().equals(cv.getVid()));
                if (!exists && cv.isRecommendable()) {
                    result.add(new RecalledCandidate(cv, CHANNEL_NAME, 0.8, "热门精选"));
                }
            }
        }

        return result;
    }
}
