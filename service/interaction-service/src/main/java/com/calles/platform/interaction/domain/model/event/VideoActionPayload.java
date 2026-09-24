package com.calles.platform.interaction.domain.model.event;

/**
 * 视频维度交互行为事件载荷，契约版本 v1。
 * 遵循 interaction.video-action 标准交互流规范。
 *
 * @param userId 交互发起人账号 ID (登录用户)
 * @param vid 目标视频业务公开短码
 * @param action 交互动作类型 (LIKE, STAR, PLAY, SHARE)
 * @param state 动作状态 (ACTIVE: 生效/点赞/首藏/播放/分享, INACTIVE: 取消/取消赞/完全取消收藏)
 */
public record VideoActionPayload(
        String userId,
        String vid,
        String action,
        String state
) {
    public static final String ACTION_LIKE = "LIKE";
    public static final String ACTION_STAR = "STAR";
    public static final String ACTION_PLAY = "PLAY";
    public static final String ACTION_PLAY_START = "PLAY_START";
    public static final String ACTION_PLAY_COMPLETE = "PLAY_COMPLETE";
    public static final String ACTION_SHARE = "SHARE";

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_INACTIVE = "INACTIVE";

    /**
     * 构造点赞生效载荷。
     *
     * @param userId 点赞用户 ID
     * @param vid 视频编码
     * @return 点赞生效载荷
     */
    public static VideoActionPayload like(String userId, String vid) {
        return new VideoActionPayload(userId, vid, ACTION_LIKE, STATE_ACTIVE);
    }

    /**
     * 构造取消点赞载荷。
     *
     * @param userId 取消点赞用户 ID
     * @param vid 视频编码
     * @return 取消点赞载荷
     */
    public static VideoActionPayload unlike(String userId, String vid) {
        return new VideoActionPayload(userId, vid, ACTION_LIKE, STATE_INACTIVE);
    }

    /**
     * 构造视频首次收藏生效载荷。
     *
     * @param userId 收藏用户 ID
     * @param vid 视频编码
     * @return 收藏生效载荷
     */
    public static VideoActionPayload star(String userId, String vid) {
        return new VideoActionPayload(userId, vid, ACTION_STAR, STATE_ACTIVE);
    }

    /**
     * 构造视频完全取消收藏载荷 (所有收藏夹均已清空该视频)。
     *
     * @param userId 取消收藏用户 ID
     * @param vid 视频编码
     * @return 完全取消收藏载荷
     */
    public static VideoActionPayload unstar(String userId, String vid) {
        return new VideoActionPayload(userId, vid, ACTION_STAR, STATE_INACTIVE);
    }

    /**
     * 构造起播行为生效载荷（首次观看或超出 6 小时冷却期后重新访问）。
     *
     * @param userId 播放用户 ID
     * @param vid 视频编码
     * @return 起播载荷
     */
    public static VideoActionPayload playStart(String userId, String vid) {
        return new VideoActionPayload(userId, vid, ACTION_PLAY_START, STATE_ACTIVE);
    }

    /**
     * 构造完播达成载荷（播放进度达到 90%）。
     *
     * @param userId 完播用户 ID
     * @param vid 视频编码
     * @return 完播载荷
     */
    public static VideoActionPayload playComplete(String userId, String vid) {
        return new VideoActionPayload(userId, vid, ACTION_PLAY_COMPLETE, STATE_ACTIVE);
    }

    /**
     * 构造有效播放达成载荷（满足5秒有效观看门槛且突破冷却窗口的有效播放）。
     *
     * @param userId 播放用户 ID
     * @param vid 视频编码
     * @return 有效播放载荷
     */
    public static VideoActionPayload play(String userId, String vid) {
        return new VideoActionPayload(userId, vid, ACTION_PLAY, STATE_ACTIVE);
    }

    /**
     * 构造单次分享达成载荷。
     *
     * @param userId 分享用户 ID
     * @param vid 视频编码
     * @return 分享载荷
     */
    public static VideoActionPayload share(String userId, String vid) {
        return new VideoActionPayload(userId, vid, ACTION_SHARE, STATE_ACTIVE);
    }
}
