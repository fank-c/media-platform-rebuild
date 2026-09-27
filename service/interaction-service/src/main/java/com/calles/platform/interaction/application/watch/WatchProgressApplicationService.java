package com.calles.platform.interaction.application.watch;

import com.calles.platform.interaction.domain.model.video.VideoSnapshot;
import com.calles.platform.interaction.domain.repository.VideoSnapshotRepository;
import com.calles.platform.interaction.domain.repository.WatchEventClaimRepository;
import com.calles.platform.interaction.domain.repository.WatchProgressRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 观看进度查询与历史管理应用服务。
 *
 * <p>职责边界：只承担观看进度的读路径与"隐藏展示"写路径，不参与心跳判定、不计播放量。
 * 与 {@link WatchHeartbeatApplicationService} 读写同一批表，两者共用会话与门槛口径。</p>
 *
 * <p>删除语义：删除历史只把 {@code deleted} 置 1 隐藏展示，不物理删除记录，
 * 因此播放量冷却时间与事件凭据都不会被"删除后重看"重置。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchProgressApplicationService {

    private final WatchProgressRepository progressRepository;
    private final VideoSnapshotRepository videoSnapshotRepository;
    private final WatchEventClaimRepository claimRepository;

    /**
     * 查询指定视频的断点续播进度。
     *
     * @param vid 视频公开短码
     * @param userId 用户 ID
     * @return 断点进度；无记录时返回零进度，视频时长取本地快照
     */
    public WatchProgressView getProgress(String vid, String userId) {
        int duration = videoSnapshotRepository.findByVid(vid)
                .filter(VideoSnapshot::isUsable)
                .map(VideoSnapshot::getDuration)
                .orElse(0);
        boolean completed = claimRepository.findCompletedVids(userId, List.of(vid)).contains(vid);

        return progressRepository.findByUserAndVid(userId, vid)
                .map(progress -> new WatchProgressView(progress.getVid(), progress.getLastPosition(),
                        progress.getWatchedDuration(), duration, completed))
                .orElseGet(() -> new WatchProgressView(vid, 0, 0, duration, false));
    }

    /**
     * 分页查询观看历史。
     *
     * @param userId 用户 ID
     * @param page 页码，从 1 起始
     * @param size 每页条数，最大 100
     * @return 历史展示条目列表
     */
    public List<WatchHistoryEntryView> getHistoryPage(String userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = (safePage - 1) * safeSize;

        return progressRepository.findVisibleHistoryPage(userId, offset, safeSize).stream()
                .map(WatchHistoryEntryView::from)
                .toList();
    }

    /**
     * 删除单条观看历史（仅隐藏展示）。
     *
     * @param vid 视频公开短码
     * @param userId 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeHistory(String vid, String userId) {
        progressRepository.hideByUserAndVid(userId, vid);
    }

    /**
     * 清空当前用户全部观看历史（仅隐藏展示）。
     *
     * @param userId 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearAllHistory(String userId) {
        progressRepository.hideAllByUserId(userId);
    }
}
