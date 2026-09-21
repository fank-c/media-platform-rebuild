package com.calles.platform.recommend.domain.model.profile;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 用户近期观看视频值对象。
 *
 * <p>以最轻量的方式仅记录视频业务短码与观看时间戳。
 * 维护固定滑动窗口（如 20~30 条），用于离线重算兜底与推荐理由展示，不包含庞大向量。</p>
 */
@Getter
public class RecentWatchItem {

    /** 视频公开业务短码。 */
    private final String vid;

    /** 观看时间戳。 */
    private final LocalDateTime watchedAt;

    public RecentWatchItem(String vid, LocalDateTime watchedAt) {
        this.vid = Objects.requireNonNull(vid, "视频短码不能为空");
        this.watchedAt = watchedAt != null ? watchedAt : LocalDateTime.now();
    }

    public static RecentWatchItem of(String vid) {
        return new RecentWatchItem(vid, LocalDateTime.now());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecentWatchItem that = (RecentWatchItem) o;
        return Objects.equals(vid, that.vid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vid);
    }
}
