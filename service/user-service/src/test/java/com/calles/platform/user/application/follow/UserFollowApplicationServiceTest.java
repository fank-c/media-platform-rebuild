package com.calles.platform.user.application.follow;

import com.calles.platform.user.application.profile.UserProfileApplicationService;
import com.calles.platform.user.domain.follow.FollowStatus;
import com.calles.platform.user.domain.follow.RelationType;
import com.calles.platform.user.domain.follow.UserCounter;
import com.calles.platform.user.domain.follow.UserFollow;
import com.calles.platform.user.domain.profile.ProfileStatus;
import com.calles.platform.user.domain.profile.UserProfile;
import com.calles.platform.user.exception.UserProfileException;
import com.calles.platform.user.infrastructure.persistence.mapper.follow.UserCounterMapper;
import com.calles.platform.user.infrastructure.persistence.mapper.follow.UserFollowMapper;
import com.calles.platform.user.infrastructure.persistence.mapper.profile.UserProfileMapper;
import com.calles.platform.user.interfaces.http.follow.dto.FollowResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户关注与粉丝核心业务编排单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserFollowApplicationService 关注核心用例测试")
class UserFollowApplicationServiceTest {

    private static final String USER_A = "user_aaaa_1111";
    private static final String USER_B = "user_bbbb_2222";

    @Mock
    private UserFollowMapper followMapper;
    @Mock
    private UserCounterMapper counterMapper;
    @Mock
    private UserProfileMapper profileMapper;
    @Mock
    private UserProfileApplicationService profileService;
    @Mock
    private UserFollowEventPublisher eventPublisher;

    private UserFollowApplicationService service;

    @BeforeEach
    void setUp() {
        service = new UserFollowApplicationService(
                followMapper,
                counterMapper,
                profileMapper,
                profileService,
                eventPublisher
        );
    }

    private UserProfile mockProfile(String accountId) {
        UserProfile p = new UserProfile();
        p.setAccountId(accountId);
        p.setStatus(ProfileStatus.ACTIVE);
        p.setDeleted(0);
        return p;
    }

    @Test
    @DisplayName("严禁用户关注自己，直接抛出 400 校验异常")
    void cannotFollowSelf() {
        UserProfileException ex = assertThrows(UserProfileException.class,
                () -> service.follow(USER_A, USER_A));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("不能关注自己", ex.getMessage());
        verify(followMapper, never()).insertIfAbsent(any(), any(), anyInt());
        verify(counterMapper, never()).incrFollowing(any());
    }

    @Test
    @DisplayName("关注不存在或已删除的目标用户，抛出 404 异常")
    void cannotFollowNonExistentUser() {
        when(profileMapper.selectPhysicalById(USER_B)).thenReturn(null);

        UserProfileException ex = assertThrows(UserProfileException.class,
                () -> service.follow(USER_A, USER_B));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(followMapper, never()).insertIfAbsent(any(), any(), anyInt());
    }

    @Test
    @DisplayName("首次关注目标用户，成功插入、递增双方计数并发布领域事件")
    void firstFollowSuccess() {
        when(profileMapper.selectPhysicalById(USER_B)).thenReturn(mockProfile(USER_B));
        when(followMapper.selectByPair(USER_A, USER_B)).thenReturn(null);
        when(followMapper.insertIfAbsent(USER_A, USER_B, 1)).thenReturn(1);
        when(followMapper.selectByPair(USER_B, USER_A)).thenReturn(null); // B 未关注 A

        FollowResponses.Action result = service.follow(USER_A, USER_B);

        assertEquals(USER_B, result.targetUserId());
        assertEquals(FollowStatus.FOLLOWING, result.followStatus());
        assertFalse(result.mutual());

        verify(counterMapper).incrFollowing(USER_A);
        verify(counterMapper).incrFollower(USER_B);
        verify(eventPublisher).publishFollowedEvent(USER_A, USER_B);
    }

    @Test
    @DisplayName("重复关注幂等性：已处于有效关注时，不重复累加计数与发布事件")
    void repeatFollowIsIdempotent() {
        when(profileMapper.selectPhysicalById(USER_B)).thenReturn(mockProfile(USER_B));
        UserFollow existing = UserFollow.builder()
                .userId(USER_A)
                .followId(USER_B)
                .followStatus(FollowStatus.FOLLOWING.getCode())
                .build();
        when(followMapper.selectByPair(USER_A, USER_B)).thenReturn(existing);

        FollowResponses.Action result = service.follow(USER_A, USER_B);

        assertEquals(FollowStatus.FOLLOWING, result.followStatus());
        verify(followMapper, never()).insertIfAbsent(any(), any(), anyInt());
        verify(counterMapper, never()).incrFollowing(any());
        verify(counterMapper, never()).incrFollower(any());
        verify(eventPublisher, never()).publishFollowedEvent(any(), any());
    }

    @Test
    @DisplayName("曾取关用户再次关注，状态条件跃迁为 1，递增双方计数并发布事件")
    void reFollowAfterUnfollowed() {
        when(profileMapper.selectPhysicalById(USER_B)).thenReturn(mockProfile(USER_B));
        UserFollow unfollowed = UserFollow.builder()
                .userId(USER_A)
                .followId(USER_B)
                .followStatus(FollowStatus.UNFOLLOWED.getCode())
                .build();
        when(followMapper.selectByPair(USER_A, USER_B)).thenReturn(unfollowed);
        when(followMapper.updateStatusConditionally(USER_A, USER_B, 0, 1)).thenReturn(1);

        // 假设 B 也关注了 A
        UserFollow bFollowsA = UserFollow.builder()
                .userId(USER_B)
                .followId(USER_A)
                .followStatus(FollowStatus.FOLLOWING.getCode())
                .build();
        when(followMapper.selectByPair(USER_B, USER_A)).thenReturn(bFollowsA);

        FollowResponses.Action result = service.follow(USER_A, USER_B);

        assertEquals(FollowStatus.FOLLOWING, result.followStatus());
        assertTrue(result.mutual()); // 互关成功
        verify(counterMapper).incrFollowing(USER_A);
        verify(counterMapper).incrFollower(USER_B);
        verify(eventPublisher).publishFollowedEvent(USER_A, USER_B);
    }

    @Test
    @DisplayName("正常取关成功，状态变更为 0，递减双方计数并广播取关事件")
    void unfollowSuccess() {
        UserFollow active = UserFollow.builder()
                .userId(USER_A)
                .followId(USER_B)
                .followStatus(FollowStatus.FOLLOWING.getCode())
                .build();
        when(followMapper.selectByPair(USER_A, USER_B)).thenReturn(active);
        when(followMapper.updateStatusConditionally(USER_A, USER_B, 1, 0)).thenReturn(1);

        FollowResponses.Action result = service.unfollow(USER_A, USER_B);

        assertEquals(FollowStatus.UNFOLLOWED, result.followStatus());
        assertFalse(result.mutual());
        verify(counterMapper).decrFollowing(USER_A);
        verify(counterMapper).decrFollower(USER_B);
        verify(eventPublisher).publishUnfollowedEvent(USER_A, USER_B);
    }

    @Test
    @DisplayName("重复取关幂等性：未关注或已取关时，直接返回成功且不扣减计数")
    void repeatUnfollowIsIdempotent() {
        when(followMapper.selectByPair(USER_A, USER_B)).thenReturn(null);

        FollowResponses.Action result = service.unfollow(USER_A, USER_B);

        assertEquals(FollowStatus.UNFOLLOWED, result.followStatus());
        verify(followMapper, never()).updateStatusConditionally(any(), any(), anyInt(), anyInt());
        verify(counterMapper, never()).decrFollowing(any());
        verify(counterMapper, never()).decrFollower(any());
        verify(eventPublisher, never()).publishUnfollowedEvent(any(), any());
    }

    @Test
    @DisplayName("双方社交关系判定：单向关注、被关注、互相关注与无关系")
    void getRelationCombinations() {
        // 场景 1：无关系
        when(followMapper.selectByPair(USER_A, USER_B)).thenReturn(null);
        when(followMapper.selectByPair(USER_B, USER_A)).thenReturn(null);
        assertEquals(RelationType.NONE, service.getRelation(USER_A, USER_B).relation());

        // 场景 2：A 关注 B
        UserFollow aToB = UserFollow.builder().userId(USER_A).followId(USER_B).followStatus(1).build();
        when(followMapper.selectByPair(USER_A, USER_B)).thenReturn(aToB);
        when(followMapper.selectByPair(USER_B, USER_A)).thenReturn(null);
        assertEquals(RelationType.FOLLOWING, service.getRelation(USER_A, USER_B).relation());

        // 场景 3：B 关注 A
        UserFollow bToA = UserFollow.builder().userId(USER_B).followId(USER_A).followStatus(1).build();
        when(followMapper.selectByPair(USER_A, USER_B)).thenReturn(null);
        when(followMapper.selectByPair(USER_B, USER_A)).thenReturn(bToA);
        assertEquals(RelationType.FOLLOWED_BY, service.getRelation(USER_A, USER_B).relation());

        // 场景 4：互相关注 MUTUAL
        when(followMapper.selectByPair(USER_A, USER_B)).thenReturn(aToB);
        when(followMapper.selectByPair(USER_B, USER_A)).thenReturn(bToA);
        assertEquals(RelationType.MUTUAL, service.getRelation(USER_A, USER_B).relation());
    }

    @Test
    @DisplayName("查询用户关系统计数据")
    void getStats() {
        UserCounter counter = UserCounter.builder()
                .accountId(USER_A)
                .followingCount(42L)
                .followerCount(128L)
                .build();
        when(counterMapper.selectByAccountId(USER_A)).thenReturn(counter);

        FollowResponses.Stats stats = service.getStats(USER_A);
        assertEquals(42L, stats.followingCount());
        assertEquals(128L, stats.followerCount());
    }
}
