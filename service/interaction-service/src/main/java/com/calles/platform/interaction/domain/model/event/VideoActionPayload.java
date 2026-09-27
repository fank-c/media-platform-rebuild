package com.calles.platform.interaction.domain.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 视频维度交互行为事件载荷，遵循 {@code interaction.video-action} 契约。
 *
 * <p>一个事件族用 {@code action} 区分行为，因此载荷按行为族扩展字段：</p>
 * <ul>
 *   <li>点赞 / 收藏 / 分享：使用 {@code state} 表达生效或取消，观看类字段为空；</li>
 *   <li>观看量资格 / 完播：使用 {@code sessionId}、{@code creditedDuration}、{@code videoDuration} 表达会话级事实，
 *       {@code state} 为空。</li>
 * </ul>
 *
 * <p>序列化时忽略空字段，因此两种行为族的 JSON 各自保持精简。消费方必须先读 {@code action} 再取对应字段，
 * 并且必须容忍未知的 {@code action}。</p>
 *
 * @param userId 交互发起人账号 ID (登录用户)
 * @param vid 目标视频业务公开短码
 * @param action 行为类型，取值见本类常量
 * @param state 状态字面量：仅点赞 / 收藏 / 分享使用，观看类行为为空
 * @param sessionId 观看会话 ID：仅观看类行为使用
 * @param creditedDuration 本会话服务端认可的有效观看时长 (秒)：仅观看类行为使用
 * @param videoDuration 本次会话使用的视频时长快照 (秒)：仅观看类行为使用
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoActionPayload(
        String userId,
        String vid,
        String action,
        String state,
        String sessionId,
        Integer creditedDuration,
        Integer videoDuration
) {

    /** 行为类型：点赞生效 / 取消点赞。 */
    public static final String ACTION_LIKE = "LIKE";

    /** 行为类型：用户维度首次收藏 / 从所有收藏夹彻底移除。 */
    public static final String ACTION_STAR = "STAR";

    /** 行为类型：分享幂等键第一次出现。 */
    public static final String ACTION_SHARE = "SHARE";

    /** 行为类型：本会话达到合格观看门槛 (30% / 5s) 并成功抢占凭据，供推荐算法消费，不直接代表增加公开播放量。 */
    public static final String ACTION_WATCH_VIEW_QUALIFIED = "WATCH_VIEW_QUALIFIED";

    /** 行为类型：本会话达成完播条件，不直接增加播放量。 */
    public static final String ACTION_WATCH_COMPLETED = "WATCH_COMPLETED";

    /** 状态字面量：生效 / 点赞 / 首藏 / 分享达成。 */
    public static final String STATE_ACTIVE = "ACTIVE";

    /** 状态字面量：取消 / 取消赞 / 完全取消收藏。 */
    public static final String STATE_INACTIVE = "INACTIVE";

    /**
     * 构造点赞生效载荷。
     *
     * @param userId 点赞用户 ID
     * @param vid 视频编码
     * @return 点赞生效载荷
     */
    public static VideoActionPayload like(String userId, String vid) {
        return stateful(userId, vid, ACTION_LIKE, STATE_ACTIVE);
    }

    /**
     * 构造取消点赞载荷。
     *
     * @param userId 取消点赞用户 ID
     * @param vid 视频编码
     * @return 取消点赞载荷
     */
    public static VideoActionPayload unlike(String userId, String vid) {
        return stateful(userId, vid, ACTION_LIKE, STATE_INACTIVE);
    }

    /**
     * 构造视频首次收藏生效载荷。
     *
     * @param userId 收藏用户 ID
     * @param vid 视频编码
     * @return 收藏生效载荷
     */
    public static VideoActionPayload star(String userId, String vid) {
        return stateful(userId, vid, ACTION_STAR, STATE_ACTIVE);
    }

    /**
     * 构造视频完全取消收藏载荷 (所有收藏夹均已清空该视频)。
     *
     * @param userId 取消收藏用户 ID
     * @param vid 视频编码
     * @return 取消收藏载荷
     */
    public static VideoActionPayload unstar(String userId, String vid) {
        return stateful(userId, vid, ACTION_STAR, STATE_INACTIVE);
    }

    /**
     * 构造单次分享达成载荷。
     *
     * @param userId 分享用户 ID
     * @param vid 视频编码
     * @return 分享载荷
     */
    public static VideoActionPayload share(String userId, String vid) {
        return stateful(userId, vid, ACTION_SHARE, STATE_ACTIVE);
    }

    /**
     * 构造播放量资格达成载荷。
     *
     * @param userId 播放用户 ID
     * @param vid 视频编码
     * @param sessionId 观看会话 ID
     * @param creditedDuration 本会话认可的有效观看时长 (秒)
     * @param videoDuration 本会话使用的视频时长快照 (秒)
     * @return 播放量资格达成载荷
     */
    public static VideoActionPayload watchViewQualified(String userId, String vid, String sessionId,
                                                        int creditedDuration, int videoDuration) {
        return new VideoActionPayload(userId, vid, ACTION_WATCH_VIEW_QUALIFIED, null,
                sessionId, creditedDuration, videoDuration);
    }

    /**
     * 构造完播达成载荷。
     *
     * @param userId 播放用户 ID
     * @param vid 视频编码
     * @param sessionId 观看会话 ID
     * @param creditedDuration 本会话认可的有效观看时长 (秒)
     * @param videoDuration 本会话使用的视频时长快照 (秒)
     * @return 完播达成载荷
     */
    public static VideoActionPayload watchCompleted(String userId, String vid, String sessionId,
                                                    int creditedDuration, int videoDuration) {
        return new VideoActionPayload(userId, vid, ACTION_WATCH_COMPLETED, null,
                sessionId, creditedDuration, videoDuration);
    }

    /**
     * 构造带状态字面量的行为载荷。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param action 行为类型
     * @param state 状态字面量
     * @return 行为载荷
     */
    private static VideoActionPayload stateful(String userId, String vid, String action, String state) {
        return new VideoActionPayload(userId, vid, action, state, null, null, null);
    }
}
