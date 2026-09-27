package com.calles.platform.interaction.exception;

import java.util.Map;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 观看会话已存在活跃实例业务异常 (409 CONFLICT)。
 *
 * <p>职责与约束：当起播请求到达但当前用户该视频仍有存活且未过期的活跃会话时抛出。
 * 附带活跃会话 ID 与已确认序号，要求客户端播放器据此恢复复用现有会话，避免刷新或重复起播刷新计数机会。</p>
 */
@Getter
public class WatchSessionActiveException extends InteractionException {

    /** 当前存活的活跃会话 ID。 */
    private final String activeSessionId;

    /** 服务端已处理的最大序号。 */
    private final Long acceptedSequence;

    /**
     * 构造活跃会话冲突异常。
     *
     * @param activeSessionId 当前存活的活跃会话 ID
     * @param acceptedSequence 服务端已处理的最大序号
     */
    public WatchSessionActiveException(String activeSessionId, Long acceptedSequence) {
        super(HttpStatus.CONFLICT, "当前存在活跃观看会话，请恢复现有会话",
                Map.of(
                        "activeSessionId", activeSessionId != null ? activeSessionId : "",
                        "acceptedSequence", acceptedSequence != null ? acceptedSequence : 0L
                ));
        this.activeSessionId = activeSessionId;
        this.acceptedSequence = acceptedSequence;
    }
}
