package com.calles.platform.interaction.domain.model.counter;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 视频互动统计计数聚合根。
 *
 * <p>由互动服务（interaction-service）作为第一责任人独占维护，解耦 content 模块的 video_content 主表。</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class VideoCounter {

    /** 视频公开业务短码 (主键)。 */
    private String vid;

    /** 累计播放/观看次数。 */
    private long viewCount;

    /** 累计有效点赞数。 */
    private long likeCount;

    /** 累计收藏数。 */
    private long starCount;

    /** 累计分享数。 */
    private long shareCount;

    /** 累计评论数 (预留)。 */
    private long commentCount;

    /** 记录创建时间。 */
    private LocalDateTime createdAt;

    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：初始化视频全新的互动统计计数器。
     *
     * @param vid 视频公开短码
     * @return 初始计数值均为 0 的计数器实例
     */
    public static VideoCounter createDefault(String vid) {
        if (vid == null || vid.isBlank()) {
            throw new IllegalArgumentException("视频业务短码不能为空");
        }
        return VideoCounter.builder()
                .vid(vid.trim())
                .viewCount(0L)
                .likeCount(0L)
                .starCount(0L)
                .shareCount(0L)
                .commentCount(0L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 原子增加播放量。
     *
     * @param delta 播放量增量 (通常为 1)
     */
    public void incrementViewCount(long delta) {
        if (delta > 0) {
            this.viewCount += delta;
            this.updatedAt = LocalDateTime.now();
        }
    }

    /**
     * 调整点赞计数。
     *
     * @param delta 点赞变动量 (+1 或 -1)
     */
    public void adjustLikeCount(long delta) {
        this.likeCount = Math.max(0L, this.likeCount + delta);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 调整收藏计数。
     *
     * @param delta 收藏变动量 (+1 或 -1)
     */
    public void adjustStarCount(long delta) {
        this.starCount = Math.max(0L, this.starCount + delta);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 原子递增分享计数。
     */
    public void incrementShareCount() {
        this.shareCount += 1L;
        this.updatedAt = LocalDateTime.now();
    }
}
