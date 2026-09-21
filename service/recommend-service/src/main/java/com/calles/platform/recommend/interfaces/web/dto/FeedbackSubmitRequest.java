package com.calles.platform.recommend.interfaces.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 客户端行为反馈流水上报请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackSubmitRequest {

    /** 视频公开业务短码。 */
    @NotBlank(message = "视频短码不能为空")
    private String vid;

    /** 行为动作类型: IMPRESSION, PLAY, SKIP, DISLIKE。 */
    @NotBlank(message = "行为类型不能为空")
    private String actionType;

    /** 实际有效播放时长 (秒)。 */
    private Integer playDuration;

    /** 视频总时长 (秒)。 */
    private Integer videoDuration;

    /** 主动负反馈原因 (如 NOT_INTERESTED, DISLIKE_AUTHOR)。 */
    private String reason;

    /** 客户端真实行为发生时间。 */
    private LocalDateTime occurredAt;
}
