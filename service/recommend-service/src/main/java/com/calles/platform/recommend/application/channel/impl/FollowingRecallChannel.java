package com.calles.platform.recommend.application.channel.impl;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.recommend.application.channel.RecommendRecallChannel;
import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.channel.model.RecalledCandidate;
import com.calles.platform.recommend.application.client.InteractionStatsClient;
import com.calles.platform.recommend.application.client.UserFollowingClient;
import com.calles.platform.recommend.application.client.dto.FollowingVideoStat;
import com.calles.platform.recommend.application.client.dto.FollowingVideoStatsRequest;
import com.calles.platform.recommend.config.FollowingRecallProperties;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 创作者关注流召回通道。
 *
 * <p>每轮固定一个时间锚点，顺序查询关注作者和自属候选池，再批量读取公开播放量并在本地评分。</p>
 */
@Slf4j
@Component
public class FollowingRecallChannel implements RecommendRecallChannel {

    /** 通道唯一业务标识。 */
    public static final String CHANNEL_NAME = "FOLLOWING";
    /** 该通道在首页混排中的目标占比。 */
    private static final int TARGET_PERCENTAGE = 10;

    private final UserFollowingClient userFollowingClient;
    private final InteractionStatsClient interactionStatsClient;
    private final CandidateVideoRepository candidateVideoRepository;
    private final FollowingRecallProperties properties;
    private final Clock clock;

    /**
     * 构造关注召回通道及其只读协作端口。
     *
     * @param userFollowingClient 用户关注作者客户端
     * @param interactionStatsClient 互动公开统计客户端
     * @param candidateVideoRepository 推荐自属候选仓储
     * @param properties 关注召回参数
     * @param clock 统一时间源
     */
    public FollowingRecallChannel(UserFollowingClient userFollowingClient,
                                  InteractionStatsClient interactionStatsClient,
                                  CandidateVideoRepository candidateVideoRepository,
                                  FollowingRecallProperties properties,
                                  Clock clock) {
        this.userFollowingClient = userFollowingClient;
        this.interactionStatsClient = interactionStatsClient;
        this.candidateVideoRepository = candidateVideoRepository;
        this.properties = properties;
        this.clock = clock;
    }

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
     * 召回关注作者在固定窗口内的最新作品并按时间与播放量排序。
     *
     * @param context 登录用户召回上下文
     * @param count 本次输出上限
     * @return 关注候选，按通道分数降序排列
     */
    @Override
    public List<RecalledCandidate> recall(RecallContext context, int count) {
        if (!supports(context) || count <= 0) {
            return Collections.emptyList();
        }

        // 步骤 1：整轮只读取一次时间，保证 SQL 上界、年龄计算和排序使用同一锚点。
        LocalDateTime anchorTime = LocalDateTime.now(clock);
        LocalDateTime windowStart = anchorTime.minusHours(properties.getWindowHours());

        List<String> authorIds;
        try {
            ApiResponse<List<String>> response = userFollowingClient.getRecentFollowingIds(context.getUserId());
            if (response == null || response.code() != 200 || response.data() == null) {
                log.warn("关注作者查询返回无效结果: userId={}, code={}", context.getUserId(),
                        response == null ? null : response.code());
                return Collections.emptyList();
            }
            authorIds = response.data().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(1000)
                    .toList();
        } catch (Exception ex) {
            log.warn("关注作者查询失败，关注通道降级为空: userId={}, error={}", context.getUserId(), ex.getMessage());
            return Collections.emptyList();
        }
        if (authorIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 步骤 2：空作者集合已在上一步短路，候选查询只访问推荐自属表并限制候选池。
        List<CandidateVideo> candidates = candidateVideoRepository.findRecentActiveByAuthorIds(
                authorIds, windowStart, anchorTime, properties.getCandidateLimit());
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<CandidateVideo> validCandidates = candidates.stream()
                .filter(candidate -> isWithinWindow(candidate, windowStart, anchorTime))
                .filter(candidate -> candidate.getVid() != null && !candidate.getVid().isBlank())
                .toList();
        if (validCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 步骤 3：一次批量读取互动快照；失败时保持候选，仅将所有播放量降为零。
        Map<String, Long> viewCounts = loadViewCounts(validCandidates);
        List<RecalledCandidate> result = new ArrayList<>();
        Set<String> seenVids = new HashSet<>();
        for (CandidateVideo candidate : validCandidates) {
            if (!seenVids.add(candidate.getVid())) {
                continue;
            }
            long viewCount = Math.max(0L, viewCounts.getOrDefault(candidate.getVid(), 0L));
            double ageHours = Duration.between(candidate.getPublishedAt(), anchorTime).toMillis() / 3_600_000.0;
            double score = (1.0 + Math.log1p(viewCount))
                    * Math.pow(2.0, -ageHours / properties.getHalfLifeHours());
            result.add(new RecalledCandidate(candidate, CHANNEL_NAME, score, "来自你关注的作者"));
        }

        result.sort(Comparator.comparingDouble(RecalledCandidate::getScore).reversed()
                .thenComparing(item -> item.getCandidate().getPublishedAt(), Comparator.reverseOrder())
                .thenComparing(item -> item.getCandidate().getVid()));
        return result.subList(0, Math.min(count, result.size()));
    }

    /**
     * 校验候选发布时间位于固定时间窗口内。
     */
    private boolean isWithinWindow(CandidateVideo candidate, LocalDateTime windowStart, LocalDateTime anchorTime) {
        return candidate.getPublishedAt() != null
                && !candidate.getPublishedAt().isBefore(windowStart)
                && !candidate.getPublishedAt().isAfter(anchorTime);
    }

    /**
     * 批量读取播放量并处理统计缺项、负数和远程失败。
     */
    private Map<String, Long> loadViewCounts(List<CandidateVideo> candidates) {
        List<String> vids = candidates.stream().map(CandidateVideo::getVid).distinct().toList();
        try {
            ApiResponse<Map<String, FollowingVideoStat>> response = interactionStatsClient.getVideoStats(
                    new FollowingVideoStatsRequest(vids));
            if (response == null || response.code() != 200 || response.data() == null) {
                return Collections.emptyMap();
            }
            Map<String, Long> result = new HashMap<>();
            response.data().forEach((key, stat) -> {
                if (key == null || stat == null || stat.viewCount() == null) {
                    return;
                }
                if (stat.viewCount() < 0) {
                    log.warn("互动统计返回负播放量，按零聚合: vid={}", key);
                    result.put(key, 0L);
                } else {
                    result.put(key, stat.viewCount());
                }
            });
            return result;
        } catch (Exception ex) {
            log.warn("批量获取关注作品播放量失败，按零播放量排序: candidateCount={}, error={}",
                    candidates.size(), ex.getMessage());
            return Collections.emptyMap();
        }
    }
}
