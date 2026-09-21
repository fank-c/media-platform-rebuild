package com.calles.platform.recommend.domain.model;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CandidateVideo 推荐候选实体行为与状态流转单元测试。
 */
@DisplayName("CandidateVideo 推荐候选实体业务测试")
class CandidateVideoTest {

    @Test
    @DisplayName("工厂构建新发布候选视频成功，初始状态为 ACTIVE")
    void shouldCreatePublishedSuccessfully() {
        LocalDateTime now = LocalDateTime.now();
        CandidateVideo candidate = CandidateVideo.createPublished(
                "c_001",
                "vid_100",
                "cv_abc123",
                "author_01",
                "tag_dom_01",
                "tag_top_01,tag_top_02",
                now
        );

        assertThat(candidate.getId()).isEqualTo("c_001");
        assertThat(candidate.getVideoId()).isEqualTo("vid_100");
        assertThat(candidate.getVid()).isEqualTo("cv_abc123");
        assertThat(candidate.getAuthorId()).isEqualTo("author_01");
        assertThat(candidate.getDomainTagIds()).isEqualTo("tag_dom_01");
        assertThat(candidate.getTopicTagIds()).isEqualTo("tag_top_01,tag_top_02");
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.ACTIVE);
        assertThat(candidate.isRecommendable()).isTrue();
        assertThat(candidate.getPublishedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("工厂构建参数前置守卫校验")
    void shouldFailWhenEssentialFieldsBlank() {
        assertThatThrownBy(() -> CandidateVideo.createPublished("", "v", "cv", "a", "", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("候选记录ID不能为空");

        assertThatThrownBy(() -> CandidateVideo.createPublished("c", "", "cv", "a", "", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("视频内部ID不能为空");

        assertThatThrownBy(() -> CandidateVideo.createPublished("c", "v", "", "a", "", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("视频业务短码不能为空");

        assertThatThrownBy(() -> CandidateVideo.createPublished("c", "v", "cv", "", "", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("创作者ID不能为空");
    }

    @Test
    @DisplayName("候选状态流转：下线、封禁与重新激活")
    void shouldTransitionStatuses() {
        CandidateVideo candidate = CandidateVideo.createPublished(
                "c_001", "v_1", "cv_1", "a_1", null, null, null
        );
        assertThat(candidate.isRecommendable()).isTrue();

        // 下线
        candidate.markOffline();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.OFFLINE);
        assertThat(candidate.isRecommendable()).isFalse();

        // 重新激活
        candidate.activate();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.ACTIVE);
        assertThat(candidate.isRecommendable()).isTrue();

        // 封禁
        candidate.markBanned();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.BANNED);
        assertThat(candidate.isRecommendable()).isFalse();
    }

    @Test
    @DisplayName("CandidateStatus 枚举解析与异常容错")
    void shouldParseCandidateStatus() {
        assertThat(CandidateStatus.fromCode("ACTIVE")).isEqualTo(CandidateStatus.ACTIVE);
        assertThat(CandidateStatus.fromCode("offline")).isEqualTo(CandidateStatus.OFFLINE);
        assertThat(CandidateStatus.fromCode("Banned")).isEqualTo(CandidateStatus.BANNED);
        assertThat(CandidateStatus.fromCode(null)).isNull();
        assertThat(CandidateStatus.fromCode("  ")).isNull();

        assertThatThrownBy(() -> CandidateStatus.fromCode("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知候选池状态代码");
    }
}
