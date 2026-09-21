package com.calles.platform.recommend.domain.model.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserProfile 用户画像聚合根单元测试。
 */
@DisplayName("UserProfile 聚合根业务行为测试")
class UserProfileTest {

    @Test
    @DisplayName("initialize：初始空白画像状态健全")
    void shouldInitializeProfileProperly() {
        UserProfile profile = UserProfile.initialize("u_001", 128);

        assertThat(profile.getUserId()).isEqualTo("u_001");
        assertThat(profile.getUserVector().isEmpty()).isTrue();
        assertThat(profile.getTopicPreferences()).isEmpty();
        assertThat(profile.getDomainStates()).isEmpty();
        assertThat(profile.getRecentWatchItems()).isEmpty();
        assertThat(profile.getProfileVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("recordPositiveConsumption：综合演进向量、主题偏好、粗领域复原与观看历史")
    void shouldEvolveProfileOnConsumption() {
        UserProfile profile = UserProfile.initialize("u_001", 3);

        List<Float> videoVector = List.of(1.0f, 0.0f, 0.0f);
        List<String> topics = List.of("java", "spring");
        String domain = "programming";

        // 预先让该粗领域产生曝光未消费
        profile.recordDomainExposure(domain, false);
        profile.recordDomainExposure(domain, false);
        profile.recordDomainExposure(domain, false);
        assertThat(profile.getDomainSuppressionFactor(domain)).isEqualTo(0.8);

        // 正向消费该视频
        profile.recordPositiveConsumption("vid_101", videoVector, topics, domain, 0.2);

        // 1. 观看历史包含了该视频
        assertThat(profile.hasWatchedRecently("vid_101")).isTrue();
        assertThat(profile.getRecentWatchItems()).hasSize(1);

        // 2. 向量被初始化
        assertThat(profile.getUserVector().isEmpty()).isFalse();

        // 3. 主题偏好得分递增
        assertThat(profile.getTopicScore("java")).isEqualTo(TopicPreference.DEFAULT_INCREMENT);
        assertThat(profile.getTopicScore("spring")).isEqualTo(TopicPreference.DEFAULT_INCREMENT);

        // 4. 粗领域未消费状态被复原为不打折 (1.0)
        assertThat(profile.getDomainSuppressionFactor(domain)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordPositiveConsumption：观看历史超过容量上限时自动淘汰最老记录")
    void shouldPruneRecentWatchItems() {
        UserProfile profile = UserProfile.initialize("u_001", 3);

        // 插入超过 30 条记录
        for (int i = 1; i <= 35; i++) {
            profile.recordPositiveConsumption("vid_" + i, null, null, null, 0.2);
        }

        assertThat(profile.getRecentWatchItems()).hasSize(UserProfile.MAX_RECENT_WATCH_SIZE);
        // 最新的一定在头部，最老的 vid_1 ~ vid_5 已被淘汰
        assertThat(profile.hasWatchedRecently("vid_35")).isTrue();
        assertThat(profile.hasWatchedRecently("vid_1")).isFalse();
    }

    @Test
    @DisplayName("recordDomainExposure：连续未消费达到门限触发弱负向折扣")
    void shouldApplySuppressionFactor() {
        UserProfile profile = UserProfile.initialize("u_001", 3);
        String domain = "sports";

        assertThat(profile.getDomainSuppressionFactor(domain)).isEqualTo(1.0);

        // 连续未消费 2 次，未达门限
        profile.recordDomainExposure(domain, false);
        profile.recordDomainExposure(domain, false);
        assertThat(profile.getDomainSuppressionFactor(domain)).isEqualTo(1.0);

        // 第 3 次未消费，触发 8 折
        profile.recordDomainExposure(domain, false);
        assertThat(profile.getDomainSuppressionFactor(domain)).isEqualTo(0.8);

        // 第 5 次未消费，触发 6 折
        profile.recordDomainExposure(domain, false);
        profile.recordDomainExposure(domain, false);
        assertThat(profile.getDomainSuppressionFactor(domain)).isEqualTo(0.6);

        // 发生一次有效消费，复原
        profile.recordDomainExposure(domain, true);
        assertThat(profile.getDomainSuppressionFactor(domain)).isEqualTo(1.0);
    }
}
