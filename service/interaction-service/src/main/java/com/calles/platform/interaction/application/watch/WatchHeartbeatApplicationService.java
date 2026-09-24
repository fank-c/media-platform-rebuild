package com.calles.platform.interaction.application.watch;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.domain.repository.WatchHistoryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频播放心跳与观看历史应用服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>统一心跳驱动：前端全程仅需周期调用心跳接口；初次访问自动建立历史并累加播放量，离开超过防刷周期（默认 6 小时）再次访问自动计入新播放；</li>
 *   <li>进度与完播维护：持续更新播放头断点位置；进度达到 90% 时通过数据库 CAS 原子置位，严格单次触发完播事件；</li>
 *   <li>历史查询与清理：提供断点查询、历史分页与删除。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class WatchHeartbeatApplicationService {

    private final WatchHistoryRepository historyRepository;
    private final VideoCounterRepository counterRepository;
    private final InteractionEventPublisher eventPublisher;
    private final Duration repeatWindow;

    public WatchHeartbeatApplicationService(
            WatchHistoryRepository historyRepository,
            VideoCounterRepository counterRepository,
            InteractionEventPublisher eventPublisher,
            @Value("${interaction.watch.repeat-window:6h}") Duration repeatWindow) {
        this.historyRepository = historyRepository;
        this.counterRepository = counterRepository;
        this.eventPublisher = eventPublisher;
        this.repeatWindow = repeatWindow != null ? repeatWindow : Duration.ofHours(6);
    }

    /**
     * 处理播放端周期心跳上报（全生命周期统一入口）。
     *
     * <p>处理逻辑：
     * 1. 若无历史记录：新建历史，自增播放量，发布 PLAY_START 起播事件；
     * 2. 若存在历史且离开超过冷却期（默认 6 小时）：自增播放量，发布 PLAY_START 起播事件；
     * 3. 正常更新断点进度（last_position）与活跃时间（last_watch_at）；
     * 4. 进度达 90% 时执行 CAS 原子置位，确保 PLAY_COMPLETE 完播事件多端并发下仅发一次。
     * </p>
     *
     * @param vid 视频业务公开短码
     * @param userId 已鉴权登录用户 ID
     * @param position 当前播放头秒数位置
     * @param deltaDuration 距上次心跳增量秒数
     * @param videoDuration 视频总秒数
     * @return 更新后的观看历史实体
     */
    @Transactional(rollbackFor = Exception.class)
    public WatchHistory processHeartbeat(String vid, String userId, int position,
                                         int deltaDuration, int videoDuration) {
        LocalDateTime now = LocalDateTime.now();
        Optional<WatchHistory> opt = historyRepository.findPhysicalByUserAndVid(userId, vid);
        WatchHistory history;

        if (opt.isEmpty()) {
            // 首次观看该视频：建立记录，累加播放量，发布 PLAY_START 事件
            history = WatchHistory.create(userId, vid, position, deltaDuration, videoDuration);
            try {
                historyRepository.save(history);
                counterRepository.incrementViewCount(vid, 1L);
                eventPublisher.publishVideoAction(VideoActionPayload.playStart(userId, vid));
                log.info("用户 [{}] 首次观看视频 [{}]（新建历史），播放量 +1 并发布起播事件", userId, vid);
            } catch (DuplicateKeyException e) {
                // 多端（如手机电脑）同毫秒首次心跳并发插入时的冲突兜底：重新读取并进入已有历史分支
                log.debug("捕获到多端同毫秒首次心跳并发插入冲突，自动自愈转入更新分支: userId={}, vid={}", userId, vid);
                history = historyRepository.findPhysicalByUserAndVid(userId, vid)
                        .orElseThrow(() -> e);
                updateExistingHistory(history, position, deltaDuration, videoDuration, now);
            }
        } else {
            history = opt.get();
            updateExistingHistory(history, position, deltaDuration, videoDuration, now);
        }

        // 完播判定：达到 90% 阈值且尚未标记完播时，采用数据库 CAS 原子防重置位
        if (videoDuration > 0 && position >= (int) (videoDuration * WatchHistory.COMPLETION_THRESHOLD_RATIO)) {
            if (!history.isCompleted()) {
                int affected = historyRepository.markCompletedIfUncompleted(history.getId());
                if (affected > 0) {
                    history.markCompleted();
                    eventPublisher.publishVideoAction(VideoActionPayload.playComplete(userId, vid));
                    log.info("用户 [{}] 针对视频 [{}] 达成完播 (CAS原子置位成功)，发布完播事件", userId, vid);
                }
            }
        }

        return history;
    }

    /**
     * 起播辅助方法（与心跳共用统一逻辑）。
     *
     * @param vid 视频编码
     * @param userId 用户 ID
     * @return 观看历史实体
     */
    @Transactional(rollbackFor = Exception.class)
    public WatchHistory startPlay(String vid, String userId) {
        return processHeartbeat(vid, userId, 0, 0, 0);
    }

    private void updateExistingHistory(WatchHistory history, int position, int deltaDuration,
                                       int videoDuration, LocalDateTime now) {
        // 检查离开该视频是否已超过防刷冷却期
        if (history.isNewWatchSession(now, repeatWindow)) {
            counterRepository.incrementViewCount(history.getVid(), 1L);
            eventPublisher.publishVideoAction(VideoActionPayload.playStart(history.getUserId(), history.getVid()));
            log.info("用户 [{}] 离开视频 [{}] 超过冷却期重新访问，播放量 +1 并发布起播事件", history.getUserId(), history.getVid());
        }

        if (history.isDeleted()) {
            history.revive(position, deltaDuration, videoDuration);
            historyRepository.revive(history);
        } else {
            history.recordHeartbeat(position, deltaDuration, videoDuration);
            historyRepository.update(history);
        }
    }

    /**
     * 获取用户在指定视频上的断点播放进度。
     *
     * @param vid 视频编码
     * @param userId 用户 ID
     * @return 观看历史 (包含 lastPosition)
     */
    public Optional<WatchHistory> getWatchProgress(String vid, String userId) {
        return historyRepository.findByUserAndVid(userId, vid);
    }

    /**
     * 分页查询用户历史观看记录列表。
     *
     * @param userId 用户 ID
     * @param page 当前页码 (从 1 起始)
     * @param size 每页大小
     * @return 历史记录列表
     */
    public List<WatchHistory> getHistoryPage(String userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = (safePage - 1) * safeSize;
        return historyRepository.findByUserId(userId, offset, safeSize);
    }

    /**
     * 删除单条视频观看历史。
     *
     * @param vid 视频编码
     * @param userId 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeHistory(String vid, String userId) {
        historyRepository.deleteByUserAndVid(userId, vid);
    }

    /**
     * 清空当前用户全部观看历史。
     *
     * @param userId 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearAllHistory(String userId) {
        historyRepository.deleteAllByUserId(userId);
    }
}
