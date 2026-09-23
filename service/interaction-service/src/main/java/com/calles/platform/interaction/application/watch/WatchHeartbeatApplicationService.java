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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频播放与观看历史应用服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>起播处理（startPlay）：在用户点进视频那一刻查历史，根据防重复窗口（默认 6 小时）决定是否递增播放计数，并返回续播断点；</li>
 *   <li>心跳上报（processHeartbeat）：播放过程中轻量更新播放断点位置（lastPosition）与完播状态判定，绝不介入播放计数增减；</li>
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
     * 用户点进视频发起起播。
     *
     * <p>检查用户针对该视频的历史记录：若无历史或距离上次活跃观看已超过防刷周期（默认 6 小时），
     * 则累加播放量并发布起播事件；若仍在冷却期内则仅更新活跃时间，不重复累加播放量。</p>
     *
     * @param vid 视频业务公开短码
     * @param userId 已鉴权登录用户 ID
     * @return 包含上次断点秒数（lastPosition）的观看历史实体
     */
    @Transactional(rollbackFor = Exception.class)
    public WatchHistory startPlay(String vid, String userId) {
        LocalDateTime now = LocalDateTime.now();
        Optional<WatchHistory> opt = historyRepository.findPhysicalByUserAndVid(userId, vid);
        WatchHistory history;

        if (opt.isEmpty()) {
            // 首次观看该视频：新建记录并增加播放量
            history = WatchHistory.createForPlay(userId, vid);
            historyRepository.save(history);
            counterRepository.incrementViewCount(vid, 1L);
            eventPublisher.publishVideoAction(VideoActionPayload.play(userId, vid));
            log.info("用户 [{}] 首次点进视频 [{}] 起播，累加播放量", userId, vid);
        } else {
            history = opt.get();
            boolean wasDeleted = history.isDeleted();
            // 已有记录：检查是否超出防刷冷却周期
            if (history.shouldIncrementViewOnPlay(now, repeatWindow)) {
                counterRepository.incrementViewCount(vid, 1L);
                eventPublisher.publishVideoAction(VideoActionPayload.play(userId, vid));
                log.info("用户 [{}] 再次点进视频 [{}]（已过冷却期），累加播放量", userId, vid);
            } else {
                log.debug("用户 [{}] 在防刷冷却期内再次点进视频 [{}]，跳过播放量累加", userId, vid);
            }
            history.recordPlayStart(now);
            if (wasDeleted) {
                historyRepository.revive(history);
            } else {
                historyRepository.update(history);
            }
        }

        return history;
    }

    /**
     * 处理播放端周期心跳上报（纯粹维护播放进度与完播状态）。
     *
     * <p>心跳绝不介入播放计数的递增，彻底消除多端（手机+电脑）并发心跳下的事件重复与防重锁负担。</p>
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
        Optional<WatchHistory> opt = historyRepository.findPhysicalByUserAndVid(userId, vid);
        WatchHistory history;

        if (opt.isEmpty()) {
            // 客户端未显式调用起播直接发送心跳时的安全保底
            history = WatchHistory.create(userId, vid, position, deltaDuration, videoDuration);
            historyRepository.save(history);
            counterRepository.incrementViewCount(vid, 1L);
            eventPublisher.publishVideoAction(VideoActionPayload.play(userId, vid));
            log.info("用户 [{}] 针对视频 [{}] 直接发送首个心跳，保底建立历史并累加播放量", userId, vid);
            return history;
        }

        history = opt.get();
        if (history.isDeleted()) {
            history.revive(position, deltaDuration, videoDuration);
            historyRepository.revive(history);
        } else {
            history.recordHeartbeat(position, deltaDuration, videoDuration);
            historyRepository.update(history);
        }

        return history;
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
