package com.calles.platform.user.interfaces.http.profile.dto;

/**
 * 管理员资料列表查询；只开放已确认用途的精确 ID、昵称前缀和资料状态条件。
 *
 * @param accountId 精确账户 ID，可空
 * @param nicknamePrefix 昵称前缀，可空
 * @param status 资料状态，可空
 */
public record AdminProfileListRequest(String accountId, String nicknamePrefix, String status) {
}
