package com.calles.platform.interaction.domain.model.watch;

/**
 * 观看事件凭据类型。
 *
 * <p>每个会话对每种类型最多产生一条凭据，凭据唯一键是合格观看与完播事件的最终防重依据。</p>
 */
public enum WatchEventType {

    /** 合格观看凭据：本会话有效观看时长达到门槛 (max(5s, 30%))，仅用于向推荐侧投递合格观看事件，不再代表已增加公开播放量。 */
    WATCH_VIEW_QUALIFIED,

    /** 完播凭据：本会话同时满足播放位置与有效观看时长双 90% 条件。 */
    WATCH_COMPLETED
}
