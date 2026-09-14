package com.calles.platform.content.domain.model.video;

import com.calles.platform.content.domain.model.CommonStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VideoContent 视频内容聚合根业务测试")
class VideoContentTest {

    @Test
    @DisplayName("创建草稿成功，初始化各快照指标与状态")
    void shouldCreateDraftSuccessfully() {
        VideoContent video = VideoContent.createDraft(
                "v001",
                "cv2026090001",
                "u001",
                "Spring Cloud 微服务精讲",
                "这是一部架构精讲视频",
                "f_video_001",
                "f_cover_001",
                720,
                "Java, 微服务 ， SpringBoot"
        );

        assertThat(video.getId()).isEqualTo("v001");
        assertThat(video.getVid()).isEqualTo("cv2026090001");
        assertThat(video.getStatus()).isEqualTo(CommonStatus.ACTIVE);
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.DRAFT);
        assertThat(video.getVisibility()).isEqualTo(ContentVisibility.PUBLIC);
        assertThat(video.getDuration()).isEqualTo(720);
        assertThat(video.getTags()).isEqualTo("Java,微服务,SpringBoot");
        assertThat(video.getViewCount()).isZero();
        assertThat(video.getLikeCount()).isZero();
        assertThat(video.getRevision()).isZero();
    }

    @Test
    @DisplayName("创建草稿时参数校验失败抛出异常")
    void shouldFailWhenInvalidArguments() {
        assertThatThrownBy(() -> VideoContent.createDraft(
                "", "cv1", "u1", "title", "desc", "vf", "cf", 100, "tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("视频ID不能为空");

        assertThatThrownBy(() -> VideoContent.createDraft(
                "id1", "", "u1", "title", "desc", "vf", "cf", 100, "tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务编码vid不能为空");

        assertThatThrownBy(() -> VideoContent.createDraft(
                "id1", "cv1", "u1", "", "desc", "vf", "cf", 100, "tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("视频标题不能为空");

        assertThatThrownBy(() -> VideoContent.createDraft(
                "id1", "cv1", "u1", "title", "desc", "vf", "cf", -1, "tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("视频时长不能为负数");
    }

    @Test
    @DisplayName("完整生命周期流转：草稿 -> 提交审核 -> 审核通过发布 -> 下架")
    void shouldFollowFullLifecycle() {
        VideoContent video = createSampleDraft();

        // 步骤 1：提交审核
        video.submitForAudit();
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.AUDITING);

        // 步骤 2：审核通过
        LocalDateTime now = LocalDateTime.now();
        video.publish(now);
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(video.getPublishedAt()).isEqualTo(now);

        // 步骤 3：主动下架
        video.takeOffline("创作者主动下架");
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.OFFLINE);
        assertThat(video.getRejectReason()).isEqualTo("创作者主动下架");
    }

    @Test
    @DisplayName("平台封禁与解封流转")
    void shouldHandleBanAndUnban() {
        VideoContent video = createSampleDraft();
        video.ban("内容违规侵权");

        assertThat(video.getStatus()).isEqualTo(CommonStatus.DISABLED);
        assertThat(video.getRejectReason()).isEqualTo("内容违规侵权");

        // 封禁状态下禁止提审
        assertThatThrownBy(video::submitForAudit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("被封禁/禁用的视频不可提交审核");

        video.unban();
        assertThat(video.getStatus()).isEqualTo(CommonStatus.ACTIVE);
    }

    @Test
    @DisplayName("审核被拒与重新提交审核流转")
    void shouldHandleRejectionAndResubmission() {
        VideoContent video = createSampleDraft();
        video.submitForAudit();

        // 审核拒绝
        video.reject("封面不合规");
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.REJECTED);
        assertThat(video.getRejectReason()).isEqualTo("封面不合规");

        // 重新编辑提交
        video.updateMetadata("修正后的标题", null, "f_new_cover", "新标签");
        video.submitForAudit();
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.AUDITING);
        assertThat(video.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("非法状态跃迁抛出 IllegalStateException")
    void shouldThrowOnInvalidStateTransition() {
        VideoContent video = createSampleDraft();

        // 草稿不能直接发布
        assertThatThrownBy(() -> video.publish(LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有审核中 (AUDITING)");

        // 草稿不能直接下架
        assertThatThrownBy(() -> video.takeOffline("原因"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有已发布 (PUBLISHED)");
    }

    @Test
    @DisplayName("更新互动快照计数")
    void shouldUpdateMetricsSnapshot() {
        VideoContent video = createSampleDraft();
        video.updateMetricsSnapshot(1000L, 50L, 10L, 20L, 5L);

        assertThat(video.getViewCount()).isEqualTo(1000L);
        assertThat(video.getLikeCount()).isEqualTo(50L);
        assertThat(video.getCommentCount()).isEqualTo(10L);
        assertThat(video.getStarCount()).isEqualTo(20L);
        assertThat(video.getShareCount()).isEqualTo(5L);
    }

    private VideoContent createSampleDraft() {
        return VideoContent.createDraft(
                "v001",
                "cv2026090001",
                "u001",
                "测试视频",
                "简介",
                "f_video",
                "f_cover",
                120,
                "测试,标签"
        );
    }
}
