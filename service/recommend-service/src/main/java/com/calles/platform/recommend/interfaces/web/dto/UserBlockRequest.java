package com.calles.platform.recommend.interfaces.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户明确屏蔽拉黑请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserBlockRequest {

    /** 屏蔽维度类型: VIDEO(视频), AUTHOR(作者), TOPIC(主题标签)。 */
    @NotBlank(message = "屏蔽类型不能为空")
    private String blockType;

    /** 屏蔽目标标识 (具体 vid / author_id / tag_id)。 */
    @NotBlank(message = "目标标识不能为空")
    private String targetId;

    /** 屏蔽原因说明。 */
    private String reason;
}
