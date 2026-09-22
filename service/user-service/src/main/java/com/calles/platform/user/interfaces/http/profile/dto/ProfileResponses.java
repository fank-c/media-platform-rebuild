package com.calles.platform.user.interfaces.http.profile.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户资料 HTTP 响应集合，按本人、公开摘要和管理视图隔离隐私字段。
 */
public final class ProfileResponses {

    private ProfileResponses() {
        // 仅作为响应类型命名空间，禁止实例化。
    }

    /** 本人完整资料响应。 */
    public record Me(String accountId, String profileState, long revision, String nickname,
                     String avatarUrl, String bio, String city, Byte gender, LocalDate birthday,
                     LocalDateTime createdAt, LocalDateTime updatedAt) { }

    /** 已认证调用方可见的最小公开详情。 */
    public record Public(String accountId, String nickname, String avatarUrl, String bio) { }

    /** 批量结果项；不可用资料不透露缺失、停用或删除的具体原因。 */
    public record BatchItem(String accountId, boolean available, Summary profile) { }

    /** 批量公开摘要。 */
    public record Summary(String accountId, String nickname, String avatarUrl) { }

    /** 管理端资料视图；不包含认证凭据或角色。 */
    public record Admin(String accountId, long revision, String nickname, String avatarUrl,
                        String bio, String city, Byte gender, LocalDate birthday, String status,
                        LocalDateTime createdAt, LocalDateTime updatedAt) { }

    /** 管理端稳定分页响应。 */
    public record AdminPage(List<Admin> records, long total, long current, long size) { }
}
