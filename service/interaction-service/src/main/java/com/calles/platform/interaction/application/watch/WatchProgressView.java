package com.calles.platform.interaction.application.watch;

/**
 * v1 心跳入口对外暴露的进度视图。
 *
 * <p>该视图刻意独立于领域实体：无论本次请求由旧模型还是新模型处理，
 * v1 控制器都以同一结构组装响应，从而保证旧客户端的响应契约不变。</p>
 *
 * @param vid 视频公开业务短码
 * @param lastPosition 断点播放位置 (秒)
 * @param watchedDuration 累计有效观看时长 (秒)
 * @param videoDuration 视频总时长 (秒)，0 表示不可用
 * @param completed 是否已完播
 */
public record WatchProgressView(
        String vid,
        int lastPosition,
        int watchedDuration,
        int videoDuration,
        boolean completed
) {
}
