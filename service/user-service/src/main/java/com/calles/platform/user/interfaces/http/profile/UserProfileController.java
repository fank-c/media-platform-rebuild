package com.calles.platform.user.interfaces.http.profile;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.user.application.profile.UserProfileApplicationService;
import com.calles.platform.user.application.security.UserAccessPolicy;
import com.calles.platform.user.interfaces.http.profile.dto.BatchProfileRequest;
import com.calles.platform.user.interfaces.http.profile.dto.ProfileResponses;
import com.calles.platform.user.interfaces.http.profile.dto.UserProfilePatchRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户前台个人资料与公开名片 HTTP 适配器，挂载于 {@code /api/users/**}。
 * 负责用户本人中心、资料更新、公开名片查询及批量资料聚合，仅处理身份入口、协议转换和响应封装。
 */
@RestController
@RequestMapping("/api/users")
public class UserProfileController {

    /** 用户资料核心应用服务。 */
    private final UserProfileApplicationService service;
    /** 用户模块显式权限策略门禁。 */
    private final UserAccessPolicy accessPolicy;

    /**
     * 构造用户前台资料控制器。
     *
     * @param service 用户资料应用服务
     * @param accessPolicy 权限校验策略门禁
     */
    public UserProfileController(UserProfileApplicationService service, UserAccessPolicy accessPolicy) {
        this.service = service;
        this.accessPolicy = accessPolicy;
    }

    /**
     * 查询本人完整资料；资料尚未初始化完成时返回 PENDING 且不写库。
     *
     * @return 本人完整私有与公开资料视图
     */
    @GetMapping("/me")
    public ApiResponse<ProfileResponses.Me> getMe() {
        // 步骤 1：必须为普通合法用户，提取当前会话用户上下文
        UserInfo user = accessPolicy.requireUser();
        // 步骤 2：委托服务层查询本人资料并返回
        return ApiResponse.ok(service.getMe(user.userId()));
    }

    /**
     * 首次完善或按乐观锁版本局部修改本人资料。
     *
     * @param request 局部修改请求载荷（含昵称、头像、简介、城市、生日与版本号）
     * @return 修改完成后的本人资料全量视图
     */
    @PatchMapping("/me")
    public ApiResponse<ProfileResponses.Me> patchMe(@RequestBody UserProfilePatchRequest request) {
        // 步骤 1：必须为普通合法用户，提取当前会话用户上下文
        UserInfo user = accessPolicy.requireUser();
        // 步骤 2：委托服务层执行防盗链校验、乐观锁校验与原子更新
        return ApiResponse.ok(service.patchMe(user.userId(), request));
    }

    /**
     * 查询指定创作者账号的最小公开资料（名片）。
     *
     * @param accountId 目标用户账户 ID
     * @return 脱敏公开资料视图（昵称、头像、简介）
     */
    @GetMapping("/{accountId}")
    public ApiResponse<ProfileResponses.Public> getPublic(@PathVariable String accountId) {
        // 步骤 1：需具备已认证身份
        accessPolicy.requireAuthenticated();
        // 步骤 2：委托服务层拉取脱敏公开摘要，屏蔽冻结账号与私有信息
        return ApiResponse.ok(service.getPublic(accountId));
    }

    /**
     * 批量查询创作者公开摘要名片，结果顺序与请求列表完全一致。
     *
     * @param request 批量查询账号 ID 集合
     * @return 创作者公开资料列表（缺失或已封禁账号标记 unavailable=true）
     */
    @PostMapping("/batch")
    public ApiResponse<List<ProfileResponses.BatchItem>> batch(@RequestBody BatchProfileRequest request) {
        // 步骤 1：需具备已认证身份
        accessPolicy.requireAuthenticated();
        // 步骤 2：按传入顺序批量聚合公开名片（集成 Cache-Aside 缓存加速）
        return ApiResponse.ok(service.batchPublic(request == null ? null : request.accountIds()));
    }
}
