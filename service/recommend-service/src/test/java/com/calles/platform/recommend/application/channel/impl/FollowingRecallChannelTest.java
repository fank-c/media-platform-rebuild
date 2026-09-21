package com.calles.platform.recommend.application.channel.impl;

import com.calles.platform.recommend.application.channel.model.RecallContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FollowingRecallChannel 关注推荐通道测试。
 */
@DisplayName("FollowingRecallChannel 关注通道测试")
class FollowingRecallChannelTest {

    private final FollowingRecallChannel channel = new FollowingRecallChannel();

    @Test
    @DisplayName("基础属性与缺省降级：通道目标配比 10%，当前阶段一安全返回空")
    void shouldReturnEmptyForPhaseOne() {
        assertThat(channel.getChannelName()).isEqualTo("FOLLOWING");
        assertThat(channel.getTargetRatioPercentage()).isEqualTo(10);

        RecallContext context = RecallContext.builder().userId("u_follow").build();
        assertThat(channel.recall(context, 2)).isEmpty();
    }
}
