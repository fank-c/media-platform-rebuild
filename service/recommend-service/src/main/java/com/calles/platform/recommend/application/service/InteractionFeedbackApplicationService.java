package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.config.RecommendEmbeddingProperties;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.VideoVector;
import com.calles.platform.recommend.domain.model.event.EventConsumedRecord;
import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import com.calles.platform.recommend.domain.model.feedback.FeedbackLog;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.EventConsumedRecordRepository;
import com.calles.platform.recommend.domain.repository.FeedbackLogRepository;
import com.calles.platform.recommend.domain.repository.UserProfileRepository;
import com.calles.platform.recommend.domain.repository.VideoVectorRepository;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionVideoActionMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 互动领域事件反馈消费与画像演进应用服务 (InteractionFeedbackApplicationService)。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>消费幂等控制</b>：依赖 {@code recommend_event_consumed} 拦截 MQ 重复投递；</li>
 *   <li><b>客观流水存证</b>：将有效互动事实忠实记录至 {@code recommend_feedback_log}；</li>
 *   <li><b>分级画像驱动</b>：按点赞、收藏、分享、合格观看、完播设定差异化 EMA 增量权重微调用户兴趣向量；</li>
 *   <li><b>回撤无惩罚</b>：取消点赞与取消收藏仅记录事实流水，不主动进行负向画像打折。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionFeedbackApplicationService {

    private static final String ACTIVE_STATE = "ACTIVE";
    private static final String INACTIVE_STATE = "INACTIVE";

    /** 消费幂等持久化仓储。 */
    private final EventConsumedRecordRepository eventConsumedRecordRepository;

    /** 行为事实日志持久化仓储。 */
    private final FeedbackLogRepository feedbackLogRepository;

    /** 用户画像聚合根仓储。 */
    private final UserProfileRepository userProfileRepository;

    /** 候选池物料元数据快照仓储。 */
    private final CandidateVideoRepository candidateVideoRepository;

    /** 视频特征向量快照仓储。 */
    private final VideoVectorRepository videoVectorRepository;

    /** 推荐系统特征维度配置。 */
    private final RecommendEmbeddingProperties embeddingProperties;

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** 点赞行为 EMA 平滑更新系数 (25% 信号强度)。 */
    public static final double LIKE_ALPHA = 0.25;

    /** 收藏行为 EMA 平滑更新系数 (30% 强意向信号)。 */
    public static final double STAR_ALPHA = 0.30;

    /** 分享行为 EMA 平滑更新系数 (35% 社交裂变超强信号)。 */
    public static final double SHARE_ALPHA = 0.35;

    /** 服务端认可有效观看行为 EMA 更新系数 (20% 基准信号)。 */
    public static final double WATCH_ALPHA = 0.20;

    /** 完播行为 EMA 平滑更新系数 (28% 强正向消费)。 */
    public static final double COMPLETE_ALPHA = 0.28;

    /**
     * 编排处理单条互动视频行为事件。
     *
     * @param message 强类型互动视频行为事件模型
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleInteractionEvent(InteractionVideoActionMessage message) {
        if (message == null || message.payload() == null) {
            return;
        }

        InteractionVideoActionMessage.Payload payload = message.payload();
        String eventId = message.eventId();
        String userId = payload.userId();
        String vid = payload.vid();
        String action = payload.action();

        // 步骤 1：利用消费幂等表执行原子防重检查
        boolean isFirstTime = eventConsumedRecordRepository.saveIfAbsent(
                EventConsumedRecord.createForVideo(eventId, message.eventType(), userId, vid, action, payload.state())
        );
        if (!isFirstTime) {
            log.debug("互动事件已被消费，幂等忽略: eventId={}, userId={}, vid={}, action={}", eventId, userId, vid, action);
            return;
        }

        // 步骤 2：加载事件处理所需的物料快照与发生时间
        EventContext context = resolveContext(message, userId, vid);

        // 步骤 3：根据行为类型路由至专用处理器
        switch (action) {
            case "LIKE" -> handleStateChange(message, context, FeedbackActionType.LIKE,
                    FeedbackActionType.UNLIKE, LIKE_ALPHA, "点赞");
            case "STAR" -> handleStateChange(message, context, FeedbackActionType.STAR,
                    FeedbackActionType.UNSTAR, STAR_ALPHA, "收藏");
            case "SHARE" -> handleShare(message, context);
            case "WATCH_VIEW_QUALIFIED" -> handleWatchEvent(message, context,
                    FeedbackActionType.WATCH_VIEW_QUALIFIED, WATCH_ALPHA, "有效观看资格");
            case "WATCH_COMPLETED" -> handleWatchEvent(message, context,
                    FeedbackActionType.WATCH_COMPLETED, COMPLETE_ALPHA, "完播");
            default -> log.warn("未知的互动行为类型，仅记录防重但不驱动画像: eventId={}, action={}", eventId, action);
        }
    }

    /**
     * 读取候选视频快照并组装本次事件处理上下文。
     */
    private EventContext resolveContext(InteractionVideoActionMessage message, String userId, String vid) {
        Optional<CandidateVideo> candidate = candidateVideoRepository.findByVid(vid);
        return new EventContext(
                userId,
                vid,
                candidate.map(CandidateVideo::getDomainTagIds).orElse(null),
                candidate.map(CandidateVideo::getTopicTagIds).orElse(null),
                candidate.map(CandidateVideo::getAuthorId).orElse(null),
                parseOccurredAt(message.occurredAt())
        );
    }

    /**
     * 处理点赞或收藏的状态变化。
     */
    private void handleStateChange(InteractionVideoActionMessage message, EventContext context,
                                   FeedbackActionType activeAction, FeedbackActionType inactiveAction,
                                   double alpha, String actionName) {
        String state = message.payload().state();
        if (ACTIVE_STATE.equalsIgnoreCase(state)) {
            recordFeedbackAndUpdateProfile(message, context, activeAction, 0, 0, alpha);
            log.info("处理{}事件成功: eventId={}, userId={}, vid={}",
                    actionName, message.eventId(), context.userId(), context.vid());
        } else if (INACTIVE_STATE.equalsIgnoreCase(state)) {
            // 取消点赞或收藏仅记录事实流水，不主动进行负向惩罚
            recordFeedbackOnly(message, context, inactiveAction, 0, 0);
            log.info("处理取消{}事件成功: eventId={}, userId={}, vid={}",
                    actionName, message.eventId(), context.userId(), context.vid());
        }
    }

    /**
     * 处理分享事件分支。
     */
    private void handleShare(InteractionVideoActionMessage message, EventContext context) {
        // 分享行为：超强裂变意愿信号，以 35% 最高权重演进画像
        recordFeedbackAndUpdateProfile(message, context, FeedbackActionType.SHARE, 0, 0, SHARE_ALPHA);
        log.info("处理分享事件成功: eventId={}, userId={}, vid={}",
                message.eventId(), context.userId(), context.vid());
    }

    /**
     * 处理服务端核验认可的有效观看或完播事件。
     */
    private void handleWatchEvent(InteractionVideoActionMessage message, EventContext context,
                                  FeedbackActionType actionType, double alpha, String eventName) {
        Integer creditedDuration = message.payload().creditedDuration();
        Integer videoDuration = message.payload().videoDuration();
        if (creditedDuration == null || videoDuration == null
                || creditedDuration <= 0 || videoDuration <= 0) {
            log.warn("{}事件时长参数非法，丢弃处理: eventId={}, userId={}, vid={}, credited={}, total={}",
                    eventName, message.eventId(), context.userId(), context.vid(),
                    creditedDuration, videoDuration);
            return;
        }

        recordFeedbackAndUpdateProfile(
                message, context, actionType, creditedDuration, videoDuration, alpha
        );
        log.info("处理{}事件成功: eventId={}, userId={}, vid={}, creditedDuration={}, videoDuration={}",
                eventName, message.eventId(), context.userId(), context.vid(),
                creditedDuration, videoDuration);
    }

    /**
     * 写入行为事实流水并驱动用户画像正向演进。
     */
    private void recordFeedbackAndUpdateProfile(InteractionVideoActionMessage message, EventContext context,
                                                FeedbackActionType actionType, int playDuration,
                                                int videoDuration, double alpha) {
        recordFeedback(message, context, actionType, playDuration, videoDuration);

        // 步骤 2：加载或初始化用户画像聚合根
        UserProfile userProfile = userProfileRepository.findByUserId(context.userId())
                .orElseGet(() -> UserProfile.initialize(context.userId(), embeddingProperties.getDimension()));

        // 步骤 3：综合推动近期观看历史、EMA 向量微调、细主题偏好累加与粗领域复原
        List<String> topicList = parseCommaList(context.topicTagIds());
        String primaryDomain = extractFirstTag(context.domainTagIds());
        List<Float> vector = fetchVideoVector(context.vid());

        userProfile.recordPositiveConsumption(context.vid(), vector, topicList, primaryDomain, alpha);
        userProfileRepository.saveOrUpdate(userProfile);

        log.debug("用户画像正向消费推进成功: userId={}, vid={}, action={}, alpha={}",
                context.userId(), context.vid(), actionType, alpha);
    }

    /**
     * 仅记录事实日志流水，不驱动画像演进。
     */
    private void recordFeedbackOnly(InteractionVideoActionMessage message, EventContext context,
                                    FeedbackActionType actionType, int playDuration, int videoDuration) {
        recordFeedback(message, context, actionType, playDuration, videoDuration);
    }

    /**
     * 创建并持久化一笔互动事实流水。
     */
    private void recordFeedback(InteractionVideoActionMessage message, EventContext context,
                                FeedbackActionType actionType, int playDuration, int videoDuration) {
        FeedbackLog feedbackLog = FeedbackLog.record(
                context.userId(), context.vid(), actionType, playDuration, videoDuration,
                context.domainTagIds(), context.topicTagIds(), context.authorId(),
                message.traceId(), context.occurredAt()
        );
        feedbackLogRepository.save(feedbackLog);
    }

    /**
     * 读取视频特征向量 JSON 并反序列化为浮点列表。
     */
    private List<Float> fetchVideoVector(String vid) {
        try {
            Optional<VideoVector> vectorOpt = videoVectorRepository.findByVid(vid);
            if (vectorOpt.isPresent() && vectorOpt.get().getVectorData() != null) {
                return objectMapper.readValue(vectorOpt.get().getVectorData(), new TypeReference<List<Float>>() {});
            }
        } catch (Exception e) {
            log.warn("读取视频特征向量解析异常: vid={}, error={}", vid, e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 逗号分隔字符串拆分为清洗后的列表。
     */
    private List<String> parseCommaList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    /**
     * 提取主领域标签 ID。
     */
    private String extractFirstTag(String raw) {
        return parseCommaList(raw).stream().findFirst().orElse(null);
    }

    /**
     * 解析事件发生时间，兼容带 'Z' 后缀的 ISO-8601 UTC 字符串及本地标准日期时间。
     */
    private LocalDateTime parseOccurredAt(String occurredAtStr) {
        if (occurredAtStr == null || occurredAtStr.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            // 兼容 ISO-8601 UTC 格式 (如 2026-09-28T03:33:41.123Z)
            return Instant.parse(occurredAtStr)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(occurredAtStr, DateTimeFormatter.ISO_DATE_TIME);
            } catch (DateTimeParseException e) {
                log.warn("解析事件发生时间失败，回退系统当前时间: occurredAt={}, error={}", occurredAtStr, e.getMessage());
                return LocalDateTime.now();
            }
        }
    }

    /**
     * 事件处理期间复用的视频元数据与发生时间上下文。
     */
    private record EventContext(
            String userId,
            String vid,
            String domainTagIds,
            String topicTagIds,
            String authorId,
            LocalDateTime occurredAt
    ) {
    }
}
