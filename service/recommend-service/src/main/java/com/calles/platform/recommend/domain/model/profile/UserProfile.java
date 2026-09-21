package com.calles.platform.recommend.domain.model.profile;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户画像聚合根 (User Profile Aggregate Root)。
 *
 * <p>维护用户在推荐系统中的当前瞬时状态。
 * 聚合了即时检索向量、细主题偏好映射、粗领域疲劳状态以及近期观看历史序列。
 * 作为推荐阶段（召回、过滤、加权打分、重排）获取用户画像的唯一领域入口。</p>
 */
@Getter
public class UserProfile {

    /** 近期观看历史最大保留条数。 */
    public static final int MAX_RECENT_WATCH_SIZE = 30;

    /** 细粒度主题偏好最大保留标签数 (Top-K 截断保护)。 */
    public static final int MAX_TOPIC_PREFERENCES_SIZE = 50;

    /** 用户账号全局唯一标识 (主键)。 */
    private final String userId;

    /** 用户即时检索向量。 */
    private UserVector userVector;

    /** 细粒度主题偏好映射 (tagId -> TopicPreference)。 */
    private final Map<String, TopicPreference> topicPreferences;

    /** 粗粒度领域状态映射 (domainId -> DomainState)。 */
    private final Map<String, DomainState> domainStates;

    /** 近期观看视频短码序列 (时间倒序，最新在最前)。 */
    private final List<RecentWatchItem> recentWatchItems;

    /** 画像乐观锁版本号。 */
    private long profileVersion;

    /** 画像创建时间。 */
    private final LocalDateTime createdAt;

    /** 画像最后更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 全参构造方法，用于仓储数据还原。
     */
    public UserProfile(String userId, UserVector userVector,
                       Map<String, TopicPreference> topicPreferences,
                       Map<String, DomainState> domainStates,
                       List<RecentWatchItem> recentWatchItems,
                       long profileVersion,
                       LocalDateTime createdAt,
                       LocalDateTime updatedAt) {
        this.userId = Objects.requireNonNull(userId, "用户ID不能为空");
        this.userVector = userVector != null ? userVector : UserVector.empty();
        this.topicPreferences = topicPreferences != null ? new ConcurrentHashMap<>(topicPreferences) : new ConcurrentHashMap<>();
        this.domainStates = domainStates != null ? new ConcurrentHashMap<>(domainStates) : new ConcurrentHashMap<>();
        this.recentWatchItems = recentWatchItems != null ? new ArrayList<>(recentWatchItems) : new ArrayList<>();
        this.profileVersion = profileVersion > 0 ? profileVersion : 1;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    /**
     * 工厂方法：为新用户创建初始空白画像 (使用平台默认 1024 维度)。
     *
     * @param userId 用户账号ID
     * @return 初始空白 UserProfile 实例
     */
    public static UserProfile initialize(String userId) {
        return initialize(userId, UserVector.DEFAULT_DIMENSION);
    }

    /**
     * 工厂方法：为新用户创建指定维度的初始空白画像。
     *
     * @param userId 用户账号ID
     * @param dimension 默认向量维度
     * @return 初始空白 UserProfile 实例
     */
    public static UserProfile initialize(String userId, int dimension) {
        return new UserProfile(
                userId,
                UserVector.empty(dimension > 0 ? dimension : UserVector.DEFAULT_DIMENSION),
                new HashMap<>(),
                new HashMap<>(),
                new ArrayList<>(),
                1L,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    /**
     * 响应用户正向消费事件 (完播、深读等)，综合推进画像演进。
     *
     * <p>包含以下步骤：
     * 1. 记入近期观看队列，超过容量时弹出最老记录；
     * 2. 通过 EMA 平滑合入新视频向量；
     * 3. 递增细粒度主题偏好得分，超过容量时末位截断淘汰；
     * 4. 消除粗领域曝光未消费计数；
     * 5. 刷新最后更新时间戳。</p>
     *
     * @param vid 消费的视频公开短码
     * @param videoVector 消费的视频特征向量 (可为 null)
     * @param topicTagIds 视频关联的主题标签ID列表 (可为 null)
     * @param domainTagId 视频关联的领域标签ID (可为 null)
     * @param emaAlpha EMA 更新系数
     */
    public void recordPositiveConsumption(String vid, List<Float> videoVector,
                                          List<String> topicTagIds, String domainTagId,
                                          double emaAlpha) {
        // 步骤 1：追加近期观看历史并控制容量
        if (vid != null && !vid.isBlank()) {
            recentWatchItems.removeIf(item -> item.getVid().equals(vid));
            recentWatchItems.add(0, RecentWatchItem.of(vid));
            while (recentWatchItems.size() > MAX_RECENT_WATCH_SIZE) {
                recentWatchItems.remove(recentWatchItems.size() - 1);
            }
        }

        // 步骤 2：EMA 合入视频向量
        if (videoVector != null && !videoVector.isEmpty()) {
            this.userVector = this.userVector.applyEma(videoVector, emaAlpha);
        }

        // 步骤 3：递增细主题偏好分
        if (topicTagIds != null) {
            for (String tagId : topicTagIds) {
                if (tagId != null && !tagId.isBlank()) {
                    topicPreferences.compute(tagId.trim(), (k, existing) ->
                            existing == null ? TopicPreference.initial(k) : existing.increment()
                    );
                }
            }
            pruneTopicPreferences();
        }

        // 步骤 4：复原粗领域未消费状态
        if (domainTagId != null && !domainTagId.isBlank()) {
            domainStates.compute(domainTagId.trim(), (k, existing) ->
                    existing == null ? new DomainState(k, 0, LocalDateTime.now()) : existing.recordConsumed()
            );
        }

        // 步骤 5：刷新更新时间
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 记录领域曝光结果 (滑过未消费 vs 有效消费)。
     *
     * @param domainTagId 粗领域标签ID
     * @param consumed 是否产生有效消费
     */
    public void recordDomainExposure(String domainTagId, boolean consumed) {
        if (domainTagId == null || domainTagId.isBlank()) {
            return;
        }
        String cleanId = domainTagId.trim();
        domainStates.compute(cleanId, (k, existing) -> {
            if (existing == null) {
                return consumed ? new DomainState(k, 0, LocalDateTime.now()) : DomainState.initial(k);
            }
            return consumed ? existing.recordConsumed() : existing.recordUnconsumed();
        });
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 检查视频是否在近期观看队列中 (用于出屏去重)。
     *
     * @param vid 视频短码
     * @return true 若近期已看过
     */
    public boolean hasWatchedRecently(String vid) {
        if (vid == null || vid.isBlank()) {
            return false;
        }
        for (RecentWatchItem item : recentWatchItems) {
            if (item.getVid().equals(vid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取指定粗领域的弱负向惩罚折扣系数。
     *
     * @param domainId 领域ID
     * @return 0.0 ~ 1.0 之间的折扣系数 (未命中时返回 1.0)
     */
    public double getDomainSuppressionFactor(String domainId) {
        if (domainId == null || !domainStates.containsKey(domainId)) {
            return 1.0;
        }
        return domainStates.get(domainId).calculateSuppressionFactor();
    }

    /**
     * 获取指定主题标签的偏好得分。
     *
     * @param tagId 主题标签ID
     * @return 得分值，不存在时返回 0.0
     */
    public double getTopicScore(String tagId) {
        if (tagId == null || !topicPreferences.containsKey(tagId)) {
            return 0.0;
        }
        return topicPreferences.get(tagId).getScore();
    }

    /**
     * 对细主题标签执行 Top-K 截断，淘汰分值最低或活跃时间最久的主题。
     */
    private void pruneTopicPreferences() {
        if (topicPreferences.size() <= MAX_TOPIC_PREFERENCES_SIZE) {
            return;
        }

        // 按照得分升序排序，优先淘汰低分项
        List<Map.Entry<String, TopicPreference>> entries = new ArrayList<>(topicPreferences.entrySet());
        entries.sort(Comparator.comparingDouble((Map.Entry<String, TopicPreference> e) -> e.getValue().getScore())
                .thenComparing(e -> e.getValue().getLastActiveAt()));

        int toRemoveCount = entries.size() - MAX_TOPIC_PREFERENCES_SIZE;
        for (int i = 0; i < toRemoveCount; i++) {
            topicPreferences.remove(entries.get(i).getKey());
        }
    }
}
