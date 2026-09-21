package com.calles.platform.recommend.interfaces.web.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户屏蔽记录网络响应体。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UserBlockResponse {

    /** 记录主键 ID。 */
    private String id;

    /** 用户账号 ID。 */
    private String userId;

    /** 屏蔽维度类型。 */
    private String blockType;

    /** 屏蔽目标标识。 */
    private String targetId;

    /** 屏蔽原因说明。 */
    private String reason;

    /** 生效时间。 */
    private LocalDateTime createdAt;
}
