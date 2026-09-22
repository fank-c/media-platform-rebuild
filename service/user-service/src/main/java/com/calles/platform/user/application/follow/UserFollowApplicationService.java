package com.calles.platform.user.application.follow;

import com.calles.platform.user.application.profile.UserProfileApplicationService;
import com.calles.platform.user.domain.follow.FollowStatus;
import com.calles.platform.user.domain.follow.RelationType;
import com.calles.platform.user.domain.follow.UserCounter;
import com.calles.platform.user.domain.follow.UserFollow;
import com.calles.platform.user.domain.profile.ProfileStatus;
import com.calles.platform.user.domain.profile.UserProfile;
import com.calles.platform.user.exception.UserProfileException;
import com.calles.platform.user.infrastructure.persistence.UserCounterMapper;
import com.calles.platform.user.infrastructure.persistence.UserFollowMapper;
import com.calles.platform.user.infrastructure.persistence.UserProfileMapper;
import com.calles.platform.user.interfaces.http.dto.FollowResponses;
import com.calles.platform.user.interfaces.http.dto.ProfileResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户关注与粉丝核心业务编排服务。
 * 负责维护关注/取关双向拓扑、自属计数原子增减、互关状态智能判定与领域事件广播。
 */
@Service
public class UserFollowApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserFollowApplicationService.class);

    private final UserFollowMapper followMapper;
    private final UserCounterMapper counterMapper;
    private final UserProfileMapper profileMapper;
    private final UserProfileApplicationService profileService;
    private final UserFollowEventPublisher eventPublisher;

    public UserFollowApplicationService(UserFollowMapper followMapper,
                                        UserCounterMapper counterMapper,
                                        UserProfileMapper profileMapper,
                                        UserProfileApplicationService profileService,
                                        UserFollowEventPublisher eventPublisher) {
        this.followMapper = followMapper;
        this.counterMapper = counterMapper;
        this.profileMapper = profileMapper;
        this.profileService = profileService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 关注指定目标用户。
     * 具备严格幂等性与防并发控制，支持软状态从已取关跃迁回已关注。
     *
     * @param userId 关注发起人账号ID (当前登录人)
     * @param targetUserId 被关注目标创作者账号ID
     * @return 关注操作结果及互关标识
     */
    @Transactional
    public FollowResponses.Action follow(String userId, String targetUserId) {
        // 步骤 1：业务约束校验——严禁用户关注自己
        if (userId == null || targetUserId == null || userId.trim().isEmpty() || targetUserId.trim().isEmpty()) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "用户标识不能为空");
        }
        if (userId.equals(targetUserId)) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "不能关注自己");
        }

        // 步骤 2：防腐校验——检查被关注用户是否存在且正常
        UserProfile targetProfile = profileMapper.selectPhysicalById(targetUserId);
        if (targetProfile == null || (targetProfile.getDeleted() != null && targetProfile.getDeleted() == 1)
                || targetProfile.getStatus() != ProfileStatus.ACTIVE) {
            throw new UserProfileException(HttpStatus.NOT_FOUND, "目标用户不存在或已被禁用");
        }

        // 步骤 3：查询当前拓扑关系记录
        UserFollow currentRelation = followMapper.selectByPair(userId, targetUserId);
        boolean stateChanged = false;

        if (currentRelation == null) {
            // 步骤 4：首次建立关注，插入物理行并设置状态为有效关注 (1)
            int rows = followMapper.insertIfAbsent(userId, targetUserId, FollowStatus.FOLLOWING.getCode());
            if (rows > 0) {
                stateChanged = true;
            } else {
                // 极端并发唯一键冲突时重查重试
                UserFollow rechecked = followMapper.selectByPair(userId, targetUserId);
                if (rechecked != null && !rechecked.isCurrentlyFollowing()) {
                    int updated = followMapper.updateStatusConditionally(userId, targetUserId,
                            FollowStatus.UNFOLLOWED.getCode(), FollowStatus.FOLLOWING.getCode());
                    stateChanged = (updated > 0);
                }
            }
        } else if (!currentRelation.isCurrentlyFollowing()) {
            // 步骤 5：历史曾关注并取关，执行条件原子跃迁 (0 -> 1)
            int updated = followMapper.updateStatusConditionally(userId, targetUserId,
                    FollowStatus.UNFOLLOWED.getCode(), FollowStatus.FOLLOWING.getCode());
            stateChanged = (updated > 0);
        } else {
            // 步骤 6：当前已处于关注状态，幂等放行
            LOGGER.info("用户已关注目标创作者，幂等忽略: userId={}, targetUserId={}", userId, targetUserId);
        }

        // 步骤 7：状态实质发生变更时，原子维护双方计数器并广播领域事件
        if (stateChanged) {
            counterMapper.incrFollowing(userId);
            counterMapper.incrFollower(targetUserId);
            eventPublisher.publishFollowedEvent(userId, targetUserId);
        }

        // 步骤 8：检查对方是否也关注了当前用户（互相关注判定）
        boolean mutual = isFollowing(targetUserId, userId);
        return new FollowResponses.Action(targetUserId, FollowStatus.FOLLOWING, mutual);
    }

    /**
     * 取消关注指定目标用户。
     * 采用软状态置零，具备天然幂等性。
     *
     * @param userId 关注发起人账号ID (当前登录人)
     * @param targetUserId 被取消关注的目标创作者账号ID
     * @return 操作结果
     */
    @Transactional
    public FollowResponses.Action unfollow(String userId, String targetUserId) {
        // 步骤 1：业务约束校验
        if (userId == null || targetUserId == null || userId.trim().isEmpty() || targetUserId.trim().isEmpty()) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "用户标识不能为空");
        }
        if (userId.equals(targetUserId)) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "不能取消关注自己");
        }

        // 步骤 2：查询当前关系状态
        UserFollow currentRelation = followMapper.selectByPair(userId, targetUserId);
        if (currentRelation == null || !currentRelation.isCurrentlyFollowing()) {
            // 记录不存在或本就已是取关状态，直接幂等返回
            return new FollowResponses.Action(targetUserId, FollowStatus.UNFOLLOWED, false);
        }

        // 步骤 3：条件原子跃迁为已取消 (1 -> 0)
        int updated = followMapper.updateStatusConditionally(userId, targetUserId,
                FollowStatus.FOLLOWING.getCode(), FollowStatus.UNFOLLOWED.getCode());
        if (updated > 0) {
            // 步骤 4：原子递减双方计数并广播取关事件
            counterMapper.decrFollowing(userId);
            counterMapper.decrFollower(targetUserId);
            eventPublisher.publishUnfollowedEvent(userId, targetUserId);
        }

        return new FollowResponses.Action(targetUserId, FollowStatus.UNFOLLOWED, false);
    }

    /**
     * 查询登录用户与目标用户之间的社交拓扑关系。
     *
     * @param currentUserId 当前登录用户ID (可为空，空时代表匿名直接返回 NONE)
     * @param targetUserId 目标用户ID
     * @return 关系判定结果
     */
    public FollowResponses.Relation getRelation(String currentUserId, String targetUserId) {
        if (currentUserId == null || targetUserId == null || currentUserId.equals(targetUserId)) {
            return new FollowResponses.Relation(targetUserId, RelationType.NONE);
        }

        boolean aFollowsB = isFollowing(currentUserId, targetUserId);
        boolean bFollowsA = isFollowing(targetUserId, currentUserId);

        RelationType relation;
        if (aFollowsB && bFollowsA) {
            relation = RelationType.MUTUAL;
        } else if (aFollowsB) {
            relation = RelationType.FOLLOWING;
        } else if (bFollowsA) {
            relation = RelationType.FOLLOWED_BY;
        } else {
            relation = RelationType.NONE;
        }

        return new FollowResponses.Relation(targetUserId, relation);
    }

    /**
     * 分页查询指定用户的关注列表（按关注时间倒序）。
     *
     * @param accountId 目标用户ID
     * @param currentUserId 当前登录用户ID (用于标注是否互关或当前登录人是否关注)
     * @param page 页码 (>= 1)
     * @param size 每页容量 (1 ~ 100)
     * @return 关注列表分页响应
     */
    public FollowResponses.Page getFollowingList(String accountId, String currentUserId, long page, long size) {
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(100, size));
        long offset = (safePage - 1) * safeSize;

        long total = followMapper.countFollowees(accountId);
        if (total == 0) {
            return new FollowResponses.Page(0, safePage, safeSize, Collections.emptyList());
        }

        List<UserFollowMapper.TargetFollowRow> rows = followMapper.selectFolloweeList(accountId, offset, safeSize);
        List<FollowResponses.FollowItem> items = assembleFollowItems(rows, accountId, currentUserId);
        return new FollowResponses.Page(total, safePage, safeSize, items);
    }

    /**
     * 分页查询指定用户的粉丝列表（按成为粉丝时间倒序）。
     *
     * @param accountId 目标用户ID
     * @param currentUserId 当前登录用户ID
     * @param page 页码 (>= 1)
     * @param size 每页容量 (1 ~ 100)
     * @return 粉丝列表分页响应
     */
    public FollowResponses.Page getFollowersList(String accountId, String currentUserId, long page, long size) {
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(100, size));
        long offset = (safePage - 1) * safeSize;

        long total = followMapper.countFollowers(accountId);
        if (total == 0) {
            return new FollowResponses.Page(0, safePage, safeSize, Collections.emptyList());
        }

        List<UserFollowMapper.TargetFollowRow> rows = followMapper.selectFollowersList(accountId, offset, safeSize);
        List<FollowResponses.FollowItem> items = assembleFollowItems(rows, accountId, currentUserId);
        return new FollowResponses.Page(total, safePage, safeSize, items);
    }

    /**
     * 查询指定用户的关注与粉丝统计快照。
     *
     * @param accountId 目标用户ID
     * @return 关系统计实体
     */
    public FollowResponses.Stats getStats(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new UserProfileException(HttpStatus.BAD_REQUEST, "用户标识不能为空");
        }
        UserCounter counter = counterMapper.selectByAccountId(accountId);
        long following = (counter != null && counter.getFollowingCount() != null) ? counter.getFollowingCount() : 0;
        long follower = (counter != null && counter.getFollowerCount() != null) ? counter.getFollowerCount() : 0;
        return new FollowResponses.Stats(accountId, following, follower);
    }

    /**
     * 内部微服务端点：拉取用户关注的所有作者ID列表（供推荐通道使用）。
     *
     * @param accountId 目标用户ID
     * @return 关注的创作者ID列表
     */
    public List<String> getAllFollowingIds(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return followMapper.selectAllFolloweeIds(accountId, 1000);
    }

    private boolean isFollowing(String fromUserId, String toUserId) {
        if (fromUserId == null || toUserId == null || fromUserId.equals(toUserId)) {
            return false;
        }
        UserFollow follow = followMapper.selectByPair(fromUserId, toUserId);
        return follow != null && follow.isCurrentlyFollowing();
    }

    /**
     * 组装列表项完整资料卡片与互关标记。
     */
    private List<FollowResponses.FollowItem> assembleFollowItems(List<UserFollowMapper.TargetFollowRow> rows,
                                                                 String baseAccountId,
                                                                 String currentUserId) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量提取用户资料
        List<String> targetIds = rows.stream().map(UserFollowMapper.TargetFollowRow::targetId).toList();
        List<ProfileResponses.BatchItem> profiles = profileService.batchPublic(targetIds);
        Map<String, ProfileResponses.BatchItem> profileMap = profiles.stream()
                .collect(Collectors.toMap(ProfileResponses.BatchItem::accountId, Function.identity(), (k1, k2) -> k1));

        List<FollowResponses.FollowItem> items = new ArrayList<>();
        for (UserFollowMapper.TargetFollowRow row : rows) {
            String targetId = row.targetId();
            ProfileResponses.BatchItem batchItem = profileMap.get(targetId);

            String nickname = null;
            String avatarUrl = null;
            String bio = null;

            if (batchItem != null && batchItem.profile() != null) {
                nickname = batchItem.profile().nickname();
                avatarUrl = batchItem.profile().avatarUrl();
            }

            // 互关判定：baseAccountId 与 targetId 之间是否互相关注
            boolean mutual = isFollowing(targetId, baseAccountId);

            items.add(new FollowResponses.FollowItem(
                    targetId,
                    nickname,
                    avatarUrl,
                    bio,
                    row.followTime(),
                    mutual
            ));
        }
        return items;
    }
}
