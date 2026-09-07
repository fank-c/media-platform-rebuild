package com.calles.platform.user.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.user.application.security.UserAccessPolicy;
import com.calles.platform.user.application.profile.UserProfileApplicationService;
import com.calles.platform.user.interfaces.http.dto.AdminProfileListRequest;
import com.calles.platform.user.interfaces.http.dto.BatchProfileRequest;
import com.calles.platform.user.interfaces.http.dto.ProfileResponses;
import com.calles.platform.user.interfaces.http.dto.UserProfilePatchRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户资料 HTTP 适配器，仅处理身份入口、协议转换和响应封装。
 */
@RestController
@RequestMapping("/api/users")
public class UserProfileController {

    /** 管理操作审计日志，不记录资料修改前后的实际值。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(UserProfileController.class);

    /** 资料应用服务。 */
    private final UserProfileApplicationService service;
    /** user-service 显式权限策略。 */
    private final UserAccessPolicy accessPolicy;

    /** 创建资料接口适配器。 */
    public UserProfileController(UserProfileApplicationService service, UserAccessPolicy accessPolicy) {
        this.service = service;
        this.accessPolicy = accessPolicy;
    }

    /** 查询本人资料；缺失时返回 PENDING 且不写库。 */
    @GetMapping("/me")
    public ApiResponse<ProfileResponses.Me> getMe() {
        UserInfo user = accessPolicy.requireUser();
        return ApiResponse.ok(service.getMe(user.userId()));
    }

    /** 首次完善或按版本局部修改本人资料。 */
    @PatchMapping("/me")
    public ApiResponse<ProfileResponses.Me> patchMe(@RequestBody UserProfilePatchRequest request) {
        UserInfo user = accessPolicy.requireUser();
        return ApiResponse.ok(service.patchMe(user.userId(), request));
    }

    /** 查询指定账户的最小公开资料。 */
    @GetMapping("/{accountId}")
    public ApiResponse<ProfileResponses.Public> getPublic(@PathVariable String accountId) {
        accessPolicy.requireAuthenticated();
        return ApiResponse.ok(service.getPublic(accountId));
    }

    /** 批量查询公开摘要，结果顺序与请求完全一致。 */
    @PostMapping("/batch")
    public ApiResponse<List<ProfileResponses.BatchItem>> batch(@RequestBody BatchProfileRequest request) {
        accessPolicy.requireAuthenticated();
        return ApiResponse.ok(service.batchPublic(request == null ? null : request.accountIds()));
    }

    /** 管理员稳定分页查询未删除资料。 */
    @PostMapping("/admin/list")
    public ApiResponse<ProfileResponses.AdminPage> listAdmin(
            @RequestBody(required = false) AdminProfileListRequest request,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        accessPolicy.requireAdmin();
        return ApiResponse.ok(service.listAdmin(request, page, size));
    }

    /** 管理员按版本修改已有正常资料，不创建或恢复资料。 */
    @PatchMapping("/admin/{accountId}")
    public ApiResponse<ProfileResponses.Admin> patchAdmin(@PathVariable String accountId,
            @RequestBody UserProfilePatchRequest request) {
        UserInfo administrator = accessPolicy.requireAdmin();
        List<String> fields = request == null ? List.of() : request.submittedFields();
        try {
            ProfileResponses.Admin response = service.patchAdmin(accountId, request);
            LOGGER.info("管理员资料修改完成，operatorId={}，targetAccountId={}，fields={}，result=success，traceId={}",
                    administrator.userId(), accountId, fields, MDC.get("traceId"));
            return ApiResponse.ok(response);
        } catch (RuntimeException exception) {
            // 失败审计只记录异常分类和字段名，不记录请求值或完整个人资料。
            LOGGER.warn("管理员资料修改失败，operatorId={}，targetAccountId={}，fields={}，result={}，traceId={}",
                    administrator.userId(), accountId, fields, exception.getClass().getSimpleName(),
                    MDC.get("traceId"));
            throw exception;
        }
    }
}
