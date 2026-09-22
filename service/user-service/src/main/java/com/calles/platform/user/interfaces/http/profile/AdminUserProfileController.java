package com.calles.platform.user.interfaces.http.profile;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.user.application.profile.UserProfileApplicationService;
import com.calles.platform.user.application.security.UserAccessPolicy;
import com.calles.platform.user.interfaces.http.profile.dto.AdminProfileListRequest;
import com.calles.platform.user.interfaces.http.profile.dto.ProfileResponses;
import com.calles.platform.user.interfaces.http.profile.dto.UserProfilePatchRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户资料治理 HTTP 适配器，挂载于 {@code /api/users/admin/**}。
 * 负责平台管理员维度的用户资料检索与违规信息纠偏，严格校验管理员角色与审计修改动作。
 */
@RestController
@RequestMapping("/api/users/admin")
public class AdminUserProfileController {

    /** 管理操作审计日志记录器，记录操作人与目标主体，严禁记录资料敏感值。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminUserProfileController.class);

    /** 用户资料核心应用服务。 */
    private final UserProfileApplicationService service;
    /** 用户模块权限策略访问门禁。 */
    private final UserAccessPolicy accessPolicy;

    /**
     * 构造管理端用户资料治理控制器。
     *
     * @param service 用户资料应用服务
     * @param accessPolicy 权限校验策略门禁
     */
    public AdminUserProfileController(UserProfileApplicationService service, UserAccessPolicy accessPolicy) {
        this.service = service;
        this.accessPolicy = accessPolicy;
    }

    /**
     * 管理员稳定分页查询平台未删除用户资料。
     *
     * @param request 分页筛选条件（含时间、状态、昵称等）
     * @param page 页码（从 1 开始，默认 1）
     * @param size 每页数量（默认 20）
     * @return 稳定排序的资料分页视图
     */
    @PostMapping("/list")
    public ApiResponse<ProfileResponses.AdminPage> listAdmin(
            @RequestBody(required = false) AdminProfileListRequest request,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 步骤 1：严格执行管理员权限门禁校验
        accessPolicy.requireAdmin();
        // 步骤 2：委托应用服务执行分页动态检索并返回
        return ApiResponse.ok(service.listAdmin(request, page, size));
    }

    /**
     * 管理员按乐观锁版本修改已有正常资料，不创建或恢复资料。
     *
     * @param accountId 目标用户账户 ID
     * @param request 局部修改请求报文（包含 revision 版本号与修改字段）
     * @return 修改完成后的最新管理端资料视图
     */
    @PatchMapping("/{accountId}")
    public ApiResponse<ProfileResponses.Admin> patchAdmin(
            @PathVariable String accountId,
            @RequestBody UserProfilePatchRequest request) {
        // 步骤 1：校验管理员身份并获取操作员上下文
        UserInfo administrator = accessPolicy.requireAdmin();
        List<String> fields = request == null ? List.of() : request.submittedFields();
        try {
            // 步骤 2：执行带版本控制的资料局部更新
            ProfileResponses.Admin response = service.patchAdmin(accountId, request);
            // 步骤 3：输出结构化成功审计日志
            LOGGER.info("管理员资料修改完成，operatorId={}，targetAccountId={}，fields={}，result=success，traceId={}",
                    administrator.userId(), accountId, fields, MDC.get("traceId"));
            return ApiResponse.ok(response);
        } catch (RuntimeException exception) {
            // 步骤 4：失败审计只记录异常分类和字段名，不记录请求值或完整个人资料
            LOGGER.warn("管理员资料修改失败，operatorId={}，targetAccountId={}，fields={}，result={}，traceId={}",
                    administrator.userId(), accountId, fields, exception.getClass().getSimpleName(),
                    MDC.get("traceId"));
            throw exception;
        }
    }
}
