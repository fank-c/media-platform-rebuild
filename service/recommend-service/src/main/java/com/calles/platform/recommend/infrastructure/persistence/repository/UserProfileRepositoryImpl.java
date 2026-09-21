package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.recommend.domain.model.profile.*;
import com.calles.platform.recommend.domain.repository.UserProfileRepository;
import com.calles.platform.recommend.infrastructure.persistence.entity.UserProfilePO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.UserProfileMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 用户画像领域仓储实现类 (UserProfileRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>防腐隔离</b>：负责 {@link UserProfile} 领域聚合根与 {@link UserProfilePO} 数据库实体间的转换；</li>
 *   <li><b>单行快照读写</b>：将用户向量、主题偏好、领域状态和观看队列序列化为 JSON 存入 {@code recommend_user_profile}；</li>
 *   <li><b>乐观锁保证</b>：并发写时校验并递增 {@code profile_version}，防止并发覆盖。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class UserProfileRepositoryImpl implements UserProfileRepository {

    private final UserProfileMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<UserProfile> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        UserProfilePO po = mapper.selectById(userId);
        if (po == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(toDomain(po));
    }

    @Override
    public void saveOrUpdate(UserProfile profile) {
        if (profile == null || profile.getUserId() == null) {
            return;
        }

        // 步骤 1：查询既有快照判断是否存在
        UserProfilePO existing = mapper.selectById(profile.getUserId());
        UserProfilePO po = toPO(profile);

        if (existing == null) {
            // 步骤 2：新用户首次初始化持久化
            po.setProfileVersion(1L);
            mapper.insert(po);
            log.debug("初始化保存新用户推荐画像快照: userId={}", profile.getUserId());
        } else {
            // 步骤 3：存在历史快照，基于乐观锁版本号执行更新
            po.setProfileVersion(existing.getProfileVersion());
            int rows = mapper.updateById(po);
            if (rows == 0) {
                log.warn("用户推荐画像更新产生乐观锁冲突: userId={}, expectedVersion={}",
                        profile.getUserId(), existing.getProfileVersion());
                throw new OptimisticLockingFailureException("用户画像快照更新并发冲突，版本已过期: userId=" + profile.getUserId());
            }
            log.debug("更新用户推荐画像快照成功: userId={}, newVersion={}", profile.getUserId(), existing.getProfileVersion() + 1);
        }
    }

    /**
     * 将数据库持久化实体还原为领域聚合根。
     */
    private UserProfile toDomain(UserProfilePO po) {
        if (po == null) {
            return null;
        }

        // 1. 还原用户检索向量
        List<Float> vectorList = parseJson(po.getUserVector(), new TypeReference<List<Float>>() {}, Collections.emptyList());
        int dimension = po.getDimension() != null ? po.getDimension() : UserVector.DEFAULT_DIMENSION;
        UserVector userVector = new UserVector(vectorList, dimension, po.getVectorUpdatedAt());

        // 2. 还原细主题偏好映射
        Map<String, TopicPreferenceItemDTO> topicDtoMap = parseJson(
                po.getTopicPreferences(),
                new TypeReference<Map<String, TopicPreferenceItemDTO>>() {},
                Collections.emptyMap()
        );
        Map<String, TopicPreference> topicPreferences = new HashMap<>();
        for (Map.Entry<String, TopicPreferenceItemDTO> entry : topicDtoMap.entrySet()) {
            TopicPreferenceItemDTO dto = entry.getValue();
            topicPreferences.put(entry.getKey(), new TopicPreference(entry.getKey(), dto.score, dto.lastActiveAt));
        }

        // 3. 还原粗领域状态映射
        Map<String, DomainStateItemDTO> domainDtoMap = parseJson(
                po.getDomainStates(),
                new TypeReference<Map<String, DomainStateItemDTO>>() {},
                Collections.emptyMap()
        );
        Map<String, DomainState> domainStates = new HashMap<>();
        for (Map.Entry<String, DomainStateItemDTO> entry : domainDtoMap.entrySet()) {
            DomainStateItemDTO dto = entry.getValue();
            domainStates.put(entry.getKey(), new DomainState(entry.getKey(), dto.exposureCount, dto.lastActiveAt));
        }

        // 4. 还原近期观看序列
        List<RecentWatchItemDTO> recentWatchDtoList = parseJson(
                po.getRecentWatchVids(),
                new TypeReference<List<RecentWatchItemDTO>>() {},
                Collections.emptyList()
        );
        List<RecentWatchItem> recentWatchItems = new ArrayList<>();
        for (RecentWatchItemDTO dto : recentWatchDtoList) {
            recentWatchItems.add(new RecentWatchItem(dto.vid, dto.watchedAt));
        }

        return new UserProfile(
                po.getUserId(),
                userVector,
                topicPreferences,
                domainStates,
                recentWatchItems,
                po.getProfileVersion() != null ? po.getProfileVersion() : 1L,
                po.getCreatedAt(),
                po.getUpdatedAt()
        );
    }

    /**
     * 将领域聚合根序列化为数据库持久化实体。
     */
    private UserProfilePO toPO(UserProfile domain) {
        UserProfilePO po = new UserProfilePO();
        po.setUserId(domain.getUserId());

        // 1. 序列化向量
        if (domain.getUserVector() != null && !domain.getUserVector().isEmpty()) {
            po.setUserVector(toJson(domain.getUserVector().getVector()));
            po.setDimension(domain.getUserVector().getDimension());
            po.setVectorUpdatedAt(domain.getUserVector().getUpdatedAt());
        } else {
            po.setDimension(UserVector.DEFAULT_DIMENSION);
        }

        // 2. 序列化细粒度主题偏好
        Map<String, TopicPreferenceItemDTO> topicDtoMap = new HashMap<>();
        for (Map.Entry<String, TopicPreference> entry : domain.getTopicPreferences().entrySet()) {
            TopicPreference p = entry.getValue();
            topicDtoMap.put(entry.getKey(), new TopicPreferenceItemDTO(p.getScore(), p.getLastActiveAt()));
        }
        po.setTopicPreferences(toJson(topicDtoMap));

        // 3. 序列化粗领域状态
        Map<String, DomainStateItemDTO> domainDtoMap = new HashMap<>();
        for (Map.Entry<String, DomainState> entry : domain.getDomainStates().entrySet()) {
            DomainState s = entry.getValue();
            domainDtoMap.put(entry.getKey(), new DomainStateItemDTO(s.getExposureCount(), s.getLastActiveAt()));
        }
        po.setDomainStates(toJson(domainDtoMap));

        // 4. 序列化近期观看队列
        List<RecentWatchItemDTO> recentWatchDtoList = new ArrayList<>();
        for (RecentWatchItem item : domain.getRecentWatchItems()) {
            recentWatchDtoList.add(new RecentWatchItemDTO(item.getVid(), item.getWatchedAt()));
        }
        po.setRecentWatchVids(toJson(recentWatchDtoList));

        po.setCreatedAt(domain.getCreatedAt());
        po.setUpdatedAt(domain.getUpdatedAt());
        return po;
    }

    private <T> String toJson(T object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            log.warn("序列化用户画像字段失败: error={}", e.getMessage());
            return null;
        }
    }

    private <T> T parseJson(String json, TypeReference<T> typeRef, T defaultValue) {
        if (json == null || json.isBlank()) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("反序列化用户画像字段失败: json={}, error={}", json, e.getMessage());
            return defaultValue;
        }
    }

    // 内部 JSON 扁平化数据传输对象
    public static class TopicPreferenceItemDTO {
        public double score;
        public LocalDateTime lastActiveAt;

        public TopicPreferenceItemDTO() {}
        public TopicPreferenceItemDTO(double score, LocalDateTime lastActiveAt) {
            this.score = score;
            this.lastActiveAt = lastActiveAt;
        }
    }

    public static class DomainStateItemDTO {
        public int exposureCount;
        public LocalDateTime lastActiveAt;

        public DomainStateItemDTO() {}
        public DomainStateItemDTO(int exposureCount, LocalDateTime lastActiveAt) {
            this.exposureCount = exposureCount;
            this.lastActiveAt = lastActiveAt;
        }
    }

    public static class RecentWatchItemDTO {
        public String vid;
        public LocalDateTime watchedAt;

        public RecentWatchItemDTO() {}
        public RecentWatchItemDTO(String vid, LocalDateTime watchedAt) {
            this.vid = vid;
            this.watchedAt = watchedAt;
        }
    }
}
