package com.calles.platform.user.application.profile;
import com.calles.platform.user.infrastructure.observability.UserOperationalMetrics;
import com.calles.platform.user.exception.UserProfileException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.calles.platform.user.domain.profile.ProfileStatus;
import com.calles.platform.user.domain.profile.UserProfile;
import com.calles.platform.user.infrastructure.persistence.mapper.profile.UserProfileMapper;
import com.calles.platform.user.interfaces.http.profile.dto.AdminProfileListRequest;
import com.calles.platform.user.interfaces.http.profile.dto.ProfileResponses;
import com.calles.platform.user.interfaces.http.profile.dto.UserProfilePatchRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户资料应用服务，统一本人、公开、管理和事件初始化入口的生命周期与并发规则。
 *
 * <p>该服务只访问 user_profile，不查询认证账户，也不承担头像上传、统计或资料删除。</p>
 */
@Service
public class UserProfileApplicationService {

    private static final Pattern ACCOUNT_ID = Pattern.compile("^[0-9a-fA-F]{32}$");

    /** 用户资料 Mapper。 */
    private final UserProfileMapper mapper;
    /** 头像公开展示策略。 */
    private final AvatarDisplayPolicy avatarPolicy;
    /** 业务日期时钟。 */
    private final Clock clock;
    /** 用户资料业务指标。 */
    private final UserOperationalMetrics metrics;

    /** 创建资料应用服务。 */
    public UserProfileApplicationService(UserProfileMapper mapper, AvatarDisplayPolicy avatarPolicy, Clock clock,
            UserOperationalMetrics metrics) {
        this.mapper = mapper;
        this.avatarPolicy = avatarPolicy;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * 纯读查询本人资料；真正缺失时返回 PENDING 视图，不产生数据库写入。
     */
    public ProfileResponses.Me getMe(String accountId) {
        validateAccountId(accountId);
        UserProfile profile = mapper.selectPhysicalById(accountId);
        if (profile == null) {
            return new ProfileResponses.Me(accountId, "PENDING", 0, null, null, null,
                    null, null, null, null, null);
        }
        assertOwnerReadable(profile);
        return toMe(profile);
    }

    /**
     * 首次创建或按 revision 局部修改本人资料；主键竞争后重新读取生命周期。
     */
    @Transactional
    public ProfileResponses.Me patchMe(String accountId, UserProfilePatchRequest request) {
        validatePatch(request);
        validateAccountId(accountId);
        // PATCH 是唯一的本人懒创建入口；GET 保持无副作用。
        UserProfile physical = mapper.selectPhysicalById(accountId);
        if (physical == null) {
            mapper.insertDefaultIfAbsent(accountId);
            physical = mapper.selectPhysicalById(accountId);
        }
        assertOwnerWritable(physical);
        updateWithRevision(accountId, request);
        return toMe(mapper.selectPhysicalById(accountId));
    }

    /**
     * 查询已认证调用方可见的公开资料；缺失、停用、删除统一返回 404。
     */
    public ProfileResponses.Public getPublic(String accountId) {
        validateAccountId(accountId);
        UserProfile profile = mapper.selectById(accountId);
        if (profile == null || profile.getStatus() != ProfileStatus.ACTIVE) {
            throw new UserProfileException(HttpStatus.NOT_FOUND, "用户资料不可用");
        }
        return new ProfileResponses.Public(profile.getAccountId(), profile.getNickname(),
                avatarPolicy.publicUrl(profile.getAvatarUrl()), profile.getBio());
    }

    /**
     * 一次读取并按请求顺序组装公开摘要，重复 ID 保留且不泄露不可用原因。
     */
    public List<ProfileResponses.BatchItem> batchPublic(List<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty() || accountIds.size() > 100) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "accountIds 数量必须在 1-100 之间");
        }
        accountIds.forEach(this::validateAccountId);
        List<String> unique = accountIds.stream().distinct().toList();
        LambdaQueryWrapper<UserProfile> query = new LambdaQueryWrapper<UserProfile>()
                .in(UserProfile::getAccountId, unique).eq(UserProfile::getStatus, ProfileStatus.ACTIVE);
        Map<String, UserProfile> profiles = new LinkedHashMap<>();
        mapper.selectList(query).forEach(profile -> profiles.put(profile.getAccountId(), profile));
        List<ProfileResponses.BatchItem> result = new ArrayList<>(accountIds.size());
        for (String accountId : accountIds) {
            UserProfile profile = profiles.get(accountId);
            ProfileResponses.Summary summary = profile == null ? null : new ProfileResponses.Summary(
                    accountId, profile.getNickname(), avatarPolicy.publicUrl(profile.getAvatarUrl()));
            result.add(new ProfileResponses.BatchItem(accountId, summary != null, summary));
        }
        return result;
    }

    /**
     * 管理员分页查询未删除资料，使用创建时间与 accountId 组成稳定排序。
     */
    public ProfileResponses.AdminPage listAdmin(AdminProfileListRequest request, long page, long size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "分页参数不合法");
        }
        LambdaQueryWrapper<UserProfile> query = new LambdaQueryWrapper<>();
        if (request != null && request.accountId() != null && !request.accountId().isBlank()) {
            validateAccountId(request.accountId());
            query.eq(UserProfile::getAccountId, request.accountId());
        }
        if (request != null && request.nicknamePrefix() != null && !request.nicknamePrefix().isBlank()) {
            String prefix = request.nicknamePrefix().trim();
            if (prefix.length() > 64) {
                throw new UserProfileException(HttpStatus.BAD_REQUEST, "昵称前缀最多 64 个字符");
            }
            query.likeRight(UserProfile::getNickname, prefix);
        }
        if (request != null && request.status() != null && !request.status().isBlank()) {
            try {
                query.eq(UserProfile::getStatus, ProfileStatus.valueOf(request.status()));
            } catch (IllegalArgumentException exception) {
                throw new UserProfileException(HttpStatus.BAD_REQUEST, "资料状态不合法");
            }
        }
        query.orderByAsc(UserProfile::getCreatedAt).orderByAsc(UserProfile::getAccountId);
        IPage<UserProfile> result = mapper.selectPage(Page.of(page, size), query);
        return new ProfileResponses.AdminPage(result.getRecords().stream().map(this::toAdmin).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 管理员只可编辑已有正常资料，不允许借管理接口建档、恢复删除或启用停用资料。
     */
    @Transactional
    public ProfileResponses.Admin patchAdmin(String accountId, UserProfilePatchRequest request) {
        validatePatch(request);
        validateAccountId(accountId);
        UserProfile profile = mapper.selectPhysicalById(accountId);
        if (profile == null || Integer.valueOf(1).equals(profile.getDeleted())) {
            throw new UserProfileException(HttpStatus.NOT_FOUND, "用户资料不可用");
        }
        if (profile.getStatus() != ProfileStatus.ACTIVE) {
            throw new UserProfileException(HttpStatus.CONFLICT, "停用资料不能通过编辑接口恢复");
        }
        updateWithRevision(accountId, request);
        return toAdmin(mapper.selectPhysicalById(accountId));
    }

    /**
     * 事件和历史补齐共用的初始化规则：只创建物理缺失记录，不覆盖或复活任何已有记录。
     */
    public String initializeIfPhysicallyAbsent(String accountId) {
        validateAccountId(accountId);
        UserProfile profile = mapper.selectPhysicalById(accountId);
        if (profile != null) {
            return Integer.valueOf(1).equals(profile.getDeleted()) ? "SKIPPED_DELETED"
                    : profile.getStatus() == ProfileStatus.DISABLED ? "SKIPPED_DISABLED" : "ALREADY_EXISTS";
        }
        int inserted = mapper.insertDefaultIfAbsent(accountId);
        if (inserted == 1) {
            return "CREATED";
        }
        UserProfile raced = mapper.selectPhysicalById(accountId);
        if (raced == null) {
            throw new IllegalStateException("资料初始化竞争后记录仍不存在");
        }
        return Integer.valueOf(1).equals(raced.getDeleted()) ? "SKIPPED_DELETED"
                : raced.getStatus() == ProfileStatus.DISABLED ? "SKIPPED_DISABLED" : "ALREADY_EXISTS";
    }

    /** 根据存在标记执行条件更新，0 行统一视为版本或生命周期竞争。 */
    private void updateWithRevision(String accountId, UserProfilePatchRequest request) {
        int updated = mapper.updateEditableFields(accountId, request.getRevision(),
                request.isNicknamePresent(), normalizeText(request.getNickname(), 64, "nickname", true),
                request.isBioPresent(), normalizeText(request.getBio(), 500, "bio", false),
                request.isCityPresent(), normalizeText(request.getCity(), 100, "city", false),
                request.isBirthdayPresent(), request.getBirthday());
        if (updated != 1) {
            metrics.recordRevisionConflict();
            throw new UserProfileException(HttpStatus.CONFLICT, "资料版本已变化，请重新获取后再修改");
        }
    }

    /** 校验 PATCH 版本、字段存在性和生日上限。 */
    private void validatePatch(UserProfilePatchRequest request) {
        if (request == null || request.getRevision() == null || request.getRevision() < 0) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "revision 必须为非负整数");
        }
        if (!request.hasEditableField()) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "至少提交一个可修改字段");
        }
        if (request.isBirthdayPresent() && request.getBirthday() != null
                && request.getBirthday().isAfter(LocalDate.now(clock))) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "birthday 不能晚于当前日期");
        }
    }

    /** 清理文本并验证长度；昵称非 null 时不允许清理后为空。 */
    private String normalizeText(String value, int max, String field, boolean nonBlank) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (nonBlank && normalized.isEmpty()) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, field + " 不能为空白文本");
        }
        if (normalized.length() > max) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, field + " 长度超过限制");
        }
        return normalized;
    }

    /** 校验当前认证主体 ID 格式，避免无界输入进入 SQL 和日志。 */
    private void validateAccountId(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "accountId 格式不合法");
        }
    }

    /** 本人读取时区分停用和删除状态。 */
    private void assertOwnerReadable(UserProfile profile) {
        if (Integer.valueOf(1).equals(profile.getDeleted())) {
            throw new UserProfileException(HttpStatus.GONE, "本人资料已删除");
        }
        if (profile.getStatus() != ProfileStatus.ACTIVE) {
            throw new UserProfileException(HttpStatus.FORBIDDEN, "本人资料已停用");
        }
    }

    /** 本人写入沿用与读取一致的生命周期约束。 */
    private void assertOwnerWritable(UserProfile profile) {
        if (profile == null) {
            throw new IllegalStateException("资料初始化后记录不存在");
        }
        assertOwnerReadable(profile);
    }

    /** 转换本人视图，头像同样经过可信来源过滤。 */
    private ProfileResponses.Me toMe(UserProfile profile) {
        return new ProfileResponses.Me(profile.getAccountId(), "READY", profile.getRevision(),
                profile.getNickname(), avatarPolicy.publicUrl(profile.getAvatarUrl()), profile.getBio(),
                profile.getCity(), profile.getGender(), profile.getBirthday(), profile.getCreatedAt(),
                profile.getUpdatedAt());
    }

    /** 转换管理视图，不包含逻辑删除行或认证域字段。 */
    private ProfileResponses.Admin toAdmin(UserProfile profile) {
        return new ProfileResponses.Admin(profile.getAccountId(), profile.getRevision(), profile.getNickname(),
                avatarPolicy.publicUrl(profile.getAvatarUrl()), profile.getBio(), profile.getCity(),
                profile.getGender(), profile.getBirthday(), profile.getStatus().getValue(),
                profile.getCreatedAt(), profile.getUpdatedAt());
    }
}
