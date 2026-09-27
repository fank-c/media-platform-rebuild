package com.calles.platform.interaction.application.watch;

/**
 * 观看心跳请求命令对象。
 *
 * <p>用于承载 HTTP 层解析校验后的起播与心跳参数：
 * <ul>
 *   <li>起播请求：{@code sessionId} 为空，{@code sequence} 为 0，{@code deltaDuration} 为 0，携带必填的 {@code startRequestKey}；</li>
 *   <li>后续心跳：{@code sessionId} 必填，{@code sequence} 必须递增，{@code startRequestKey} 为空。</li>
 * </ul>
 * 视频总时长一律取自 interaction-service 本地快照，不接收客户端上报。</p>
 *
 * @param sessionId 客户端回传的会话 ID，起播时为空；后续心跳必须有效
 * @param sequence 客户端单调递增心跳序号，起播时为 0；后续心跳必须为正整数
 * @param position 当前播放头位置 (秒)
 * @param deltaDuration 距上次心跳的增量秒数，起播时为 0
 * @param startRequestKey 起播请求幂等键 (来自 Header Idempotency-Key，起播必填)
 */
public record WatchHeartbeatCommand(
        String sessionId,
        Long sequence,
        int position,
        Integer deltaDuration,
        String startRequestKey
) {
    public WatchHeartbeatCommand(String sessionId, Long sequence, int position, Integer deltaDuration) {
        this(sessionId, sequence, position, deltaDuration, null);
    }

    /**
     * 判断当前命令是否为起播请求。
     *
     * @return true 表示为起播请求
     */
    public boolean isStartPlay() {
        return sessionId == null || sessionId.isBlank();
    }
}
