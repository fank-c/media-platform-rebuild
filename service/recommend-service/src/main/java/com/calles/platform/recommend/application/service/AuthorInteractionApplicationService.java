package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.domain.model.event.EventConsumedRecord;
import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import com.calles.platform.recommend.domain.model.feedback.FeedbackLog;
import com.calles.platform.recommend.domain.repository.EventConsumedRecordRepository;
import com.calles.platform.recommend.domain.repository.FeedbackLogRepository;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionAuthorActionMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 作者维度互动事件 (关注/取消关注) 反馈消费与画像演进应用服务 (AuthorInteractionApplicationService)。
 *
 * <p>核心职责与边界规范：
 * <ul>
 *   <li><b>消费幂等控制</b>：基于 {@code eventId} 依靠 {@code recommend_event_consumed} 拦截 MQ 重复投递；</li>
 *   <li><b>客观流水存证</b>：将关注 (FOLLOW) 与取关 (UNFOLLOW) 事实忠实记录至 {@code recommend_feedback_log}；</li>
 *   <li><b>画像演进语义</b>：
 *     <ul>
 *       <li>{@code FOLLOW + ACTIVE}：表示用户对作者产生明确长期兴趣，记录正向作者互动信号并预留画像扩展入口；</li>
 *       <li>{@code FOLLOW + INACTIVE}：用户取消关注，仅记录事实流水，不主动对作者内容施加负向惩罚。</li>
 *     </ul>
 *   </li>
 *   <li><b>严格非职责</b>：
 *     <ul>
 *       <li>不查询用户关注列表；</li>
 *       <li>不查询创作者视频作品；</li>
 *       <li>不在推荐侧维护/镜像关注关系副本；</li>
 *       <li>不参与 {@code FollowingRecallChannel} 的视频物料获取。</li>
 *     </ul>
 *   </li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorInteractionApplicationService {

    private final EventConsumedRecordRepository eventConsumedRecordRepository;
    private final FeedbackLogRepository feedbackLogRepository;

    /**
     * 编排处理单条作者维度关注互动事件。
     *
     * @param message 强类型作者互动事件模型
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleAuthorAction(InteractionAuthorActionMessage message) {
        if (message == null || message.payload() == null) {
            log.warn("作者维度互动事件消息体或载荷为空，跳过处理");
            return;
        }

        InteractionAuthorActionMessage.Payload payload = message.payload();
        String eventId = message.eventId();
        String userId = payload.userId();
        String authorId = payload.authorId();
        String action = payload.action();
        String state = payload.state();

        // 步骤 1：合法性基础校验
        if (userId == null || userId.isBlank()
                || authorId == null || authorId.isBlank()
                || !InteractionAuthorActionMessage.ACTION_FOLLOW.equalsIgnoreCase(action)) {
            log.warn("丢弃非法或未受支持的作者互动事件: eventId={}, userId={}, authorId={}, action={}",
                    eventId, userId, authorId, action);
            return;
        }

        boolean isActive = InteractionAuthorActionMessage.STATE_ACTIVE.equalsIgnoreCase(state);
        boolean isInactive = InteractionAuthorActionMessage.STATE_INACTIVE.equalsIgnoreCase(state);

        if (!isActive && !isInactive) {
            log.warn("丢弃非法状态的作者互动事件: eventId={}, state={}", eventId, state);
            return;
        }

        // 步骤 2：基于 eventId 执行原子防重检查
        boolean isFirstTime = eventConsumedRecordRepository.saveIfAbsent(
                EventConsumedRecord.createForAuthor(eventId, message.eventType(), userId, authorId, action, state)
        );
        if (!isFirstTime) {
            log.debug("作者互动事件已被消费，幂等忽略: eventId={}, userId={}, authorId={}, state={}",
                    eventId, userId, authorId, state);
            return;
        }

        // 步骤 3：解析发生时间并记录行为事实流水
        LocalDateTime occurredAt = parseOccurredAt(message.occurredAt());
        FeedbackActionType feedbackAction = isActive ? FeedbackActionType.FOLLOW : FeedbackActionType.UNFOLLOW;

        FeedbackLog feedbackLog = FeedbackLog.recordAuthorAction(
                userId, authorId, feedbackAction, message.traceId(), occurredAt
        );
        feedbackLogRepository.save(feedbackLog);

        // 步骤 4：根据关注状态驱动画像演进语义
        if (isActive) {
            // ACTIVE：用户对作者产生明确长期偏好，预留作者维度兴趣权重更新插槽
            log.info("处理作者关注正向反馈成功: eventId={}, userId={}, authorId={}, traceId={}",
                    eventId, userId, authorId, message.traceId());
            // 预留后续画像扩展：如 userProfile.recordAuthorInterest(authorId)
        } else {
            // INACTIVE：取关仅记录事实，不主动进行负向画像惩罚
            log.info("处理作者取消关注反馈成功 (仅流水留痕，不惩罚画像): eventId={}, userId={}, authorId={}, traceId={}",
                    eventId, userId, authorId, message.traceId());
        }
    }

    /**
     * 解析事件发生时间，兼容带 'Z' 后缀的 ISO-8601 UTC 字符串及本地标准日期时间。
     */
    private LocalDateTime parseOccurredAt(String occurredAtStr) {
        if (occurredAtStr == null || occurredAtStr.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return Instant.parse(occurredAtStr)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(occurredAtStr, DateTimeFormatter.ISO_DATE_TIME);
            } catch (DateTimeParseException e) {
                log.warn("解析作者互动事件发生时间失败，回退系统当前时间: occurredAt={}, error={}", occurredAtStr, e.getMessage());
                return LocalDateTime.now();
            }
        }
    }
}
