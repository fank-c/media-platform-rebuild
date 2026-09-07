package com.calles.platform.user.interfaces.http.dto;

import java.util.List;

/**
 * 批量公开资料请求。
 *
 * @param accountIds 1 至 100 个认证账户 ID，顺序和重复项均保留
 */
public record BatchProfileRequest(List<String> accountIds) {
}
