package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.calles.platform.recommend.domain.model.profile.TopicPreference;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.infrastructure.persistence.entity.UserProfilePO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.UserProfileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UserProfileRepositoryImpl 仓储实现单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileRepositoryImpl 仓储实现测试")
class UserProfileRepositoryImplTest {

    @Mock
    private UserProfileMapper userProfileMapper;

    private ObjectMapper objectMapper;
    private UserProfileRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        repository = new UserProfileRepositoryImpl(userProfileMapper, objectMapper);
    }

    @Test
    @DisplayName("findByUserId：反序列化 JSON 完整还原画像聚合根")
    void shouldFindByUserIdAndDeserialize() {
        UserProfilePO po = new UserProfilePO();
        po.setUserId("u_100");
        po.setUserVector("[0.6, 0.8]");
        po.setDimension(2);
        po.setVectorUpdatedAt(LocalDateTime.now());
        po.setTopicPreferences("{\"java\":{\"score\":2.5,\"lastActiveAt\":\"2026-09-21T09:00:00\"}}");
        po.setDomainStates("{\"tech\":{\"exposureCount\":3,\"lastActiveAt\":\"2026-09-21T09:00:00\"}}");
        po.setRecentWatchVids("[{\"vid\":\"vid_99\",\"watchedAt\":\"2026-09-21T09:00:00\"}]");
        po.setProfileVersion(5L);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());

        when(userProfileMapper.selectById("u_100")).thenReturn(po);

        Optional<UserProfile> opt = repository.findByUserId("u_100");

        assertThat(opt).isPresent();
        UserProfile profile = opt.get();
        assertThat(profile.getUserId()).isEqualTo("u_100");
        assertThat(profile.getUserVector().isEmpty()).isFalse();
        assertThat(profile.getUserVector().getVector()).containsExactly(0.6f, 0.8f);
        assertThat(profile.getTopicScore("java")).isEqualTo(2.5);
        assertThat(profile.getDomainSuppressionFactor("tech")).isEqualTo(0.8);
        assertThat(profile.hasWatchedRecently("vid_99")).isTrue();
        assertThat(profile.getProfileVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("saveOrUpdate：首次初始化保存执行 insert")
    void shouldInsertWhenNewUser() {
        UserProfile profile = UserProfile.initialize("u_new");
        when(userProfileMapper.selectById("u_new")).thenReturn(null);
        when(userProfileMapper.insert(any(UserProfilePO.class))).thenReturn(1);

        repository.saveOrUpdate(profile);

        ArgumentCaptor<UserProfilePO> captor = ArgumentCaptor.forClass(UserProfilePO.class);
        verify(userProfileMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("u_new");
        assertThat(captor.getValue().getProfileVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("saveOrUpdate：既有画像正常执行 updateById")
    void shouldUpdateWhenExistingUser() {
        UserProfile profile = UserProfile.initialize("u_exist");
        profile.recordPositiveConsumption("vid_1", List.of(1.0f, 0.0f), List.of("tag_1"), "d1", 0.2);

        UserProfilePO existingPO = new UserProfilePO();
        existingPO.setUserId("u_exist");
        existingPO.setProfileVersion(3L);

        when(userProfileMapper.selectById("u_exist")).thenReturn(existingPO);
        when(userProfileMapper.updateById(any(UserProfilePO.class))).thenReturn(1);

        repository.saveOrUpdate(profile);

        ArgumentCaptor<UserProfilePO> captor = ArgumentCaptor.forClass(UserProfilePO.class);
        verify(userProfileMapper).updateById(captor.capture());
        assertThat(captor.getValue().getProfileVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("saveOrUpdate：乐观锁冲突 (影响行数为0) 抛出异常")
    void shouldThrowWhenOptimisticLockingFails() {
        UserProfile profile = UserProfile.initialize("u_conflict");

        UserProfilePO existingPO = new UserProfilePO();
        existingPO.setUserId("u_conflict");
        existingPO.setProfileVersion(2L);

        when(userProfileMapper.selectById("u_conflict")).thenReturn(existingPO);
        when(userProfileMapper.updateById(any(UserProfilePO.class))).thenReturn(0);

        assertThatThrownBy(() -> repository.saveOrUpdate(profile))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessageContaining("并发冲突");
    }
}
