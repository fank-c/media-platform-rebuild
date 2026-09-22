package com.calles.platform.user.application.profile;
import com.calles.platform.user.infrastructure.observability.UserOperationalMetrics;
import com.calles.platform.user.exception.UserProfileException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.user.domain.profile.ProfileStatus;
import com.calles.platform.user.domain.profile.UserProfile;
import com.calles.platform.user.infrastructure.persistence.mapper.profile.UserProfileMapper;
import com.calles.platform.user.interfaces.http.profile.dto.ProfileResponses;
import com.calles.platform.user.interfaces.http.profile.dto.UserProfilePatchRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 用户资料生命周期与并发规则单元测试。 */
@ExtendWith(MockitoExtension.class)
class UserProfileApplicationServiceTest {

    private static final String ACCOUNT_ID = "0123456789abcdef0123456789abcdef";

    @Mock private UserProfileMapper mapper;
    @Mock private AvatarDisplayPolicy avatarPolicy;
    @Mock private UserOperationalMetrics metrics;
    private UserProfileApplicationService service;

    /** 为每个测试创建固定业务日期的资料服务。 */
    @BeforeEach
    void setUp() {
        service = new UserProfileApplicationService(mapper, avatarPolicy,
                Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC), metrics);
    }

    /** 缺失资料的本人 GET 返回 PENDING 且绝不执行初始化写入。 */
    @Test
    void getMeMissingReturnsPendingWithoutWrite() {
        when(mapper.selectPhysicalById(ACCOUNT_ID)).thenReturn(null);
        ProfileResponses.Me response = service.getMe(ACCOUNT_ID);
        assertEquals("PENDING", response.profileState());
        verify(mapper, never()).insertDefaultIfAbsent(any());
    }

    /** 首次 PATCH 先幂等建档再按 revision=0 更新。 */
    @Test
    void firstPatchCreatesThenUpdatesByRevision() {
        UserProfile active = profile(0L, ProfileStatus.ACTIVE, 0);
        UserProfile updated = profile(1L, ProfileStatus.ACTIVE, 0);
        updated.setNickname("新昵称");
        when(mapper.selectPhysicalById(ACCOUNT_ID)).thenReturn(null, active, updated);
        when(mapper.insertDefaultIfAbsent(ACCOUNT_ID)).thenReturn(1);
        when(mapper.updateEditableFields(eq(ACCOUNT_ID), eq(0L), eq(true), eq("新昵称"),
                eq(false), eq(null), eq(false), eq(null), eq(false), eq(null))).thenReturn(1);
        UserProfilePatchRequest request = new UserProfilePatchRequest();
        request.setRevision(0L);
        request.setNickname(" 新昵称 ");

        ProfileResponses.Me response = service.patchMe(ACCOUNT_ID, request);

        assertEquals(1L, response.revision());
        assertEquals("新昵称", response.nickname());
    }

    /** 初始化事件遇到逻辑删除墓碑必须跳过。 */
    @Test
    void initializationDoesNotReviveDeletedProfile() {
        when(mapper.selectPhysicalById(ACCOUNT_ID)).thenReturn(profile(3L, ProfileStatus.ACTIVE, 1));
        assertEquals("SKIPPED_DELETED", service.initializeIfPhysicallyAbsent(ACCOUNT_ID));
        verify(mapper, never()).insertDefaultIfAbsent(any());
    }

    /** 条件更新 0 行表示版本竞争，必须返回 409。 */
    @Test
    void revisionConflictIsRejected() {
        when(mapper.selectPhysicalById(ACCOUNT_ID)).thenReturn(profile(2L, ProfileStatus.ACTIVE, 0));
        when(mapper.updateEditableFields(eq(ACCOUNT_ID), eq(1L), eq(false), eq(null),
                eq(true), eq("bio"), eq(false), eq(null), eq(false), eq(null))).thenReturn(0);
        UserProfilePatchRequest request = new UserProfilePatchRequest();
        request.setRevision(1L);
        request.setBio("bio");

        UserProfileException exception = assertThrows(UserProfileException.class,
                () -> service.patchMe(ACCOUNT_ID, request));
        assertEquals(409, exception.getStatus().value());
        verify(metrics).recordRevisionConflict();
    }

    /** 构造指定生命周期的资料快照。 */
    private UserProfile profile(long revision, ProfileStatus status, int deleted) {
        UserProfile profile = new UserProfile();
        profile.setAccountId(ACCOUNT_ID);
        profile.setRevision(revision);
        profile.setStatus(status);
        profile.setDeleted(deleted);
        return profile;
    }
}
