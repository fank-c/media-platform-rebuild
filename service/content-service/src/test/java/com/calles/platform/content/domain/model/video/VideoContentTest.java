package com.calles.platform.content.domain.model.video;

import com.calles.platform.content.domain.model.CommonStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VideoContent 视频内容聚合根业务逻辑与状态机单元测试。
 * <p>
 * 覆盖草稿创建、基础校验、提审发布下架全生命周期状态流转、治理封禁解封、非法状态跃迁保护及计数快照更新等关键逻辑。
 */
@DisplayName("VideoContent 视频内容聚合根业务测试")
class VideoContentTest {

    /**
     * 测试创建草稿成功场景，验证各初始字段、快照指标及默认状态是否符合契约。
     */
    @Test
    @DisplayName("创建草稿成功，初始化各快照指标与状态")
    void shouldCreateDraftSuccessfully() {
        // 步骤 1: 调用工厂方法创建视频草稿
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

        // 步骤 2: 校验初始标识与生命周期状态
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

    /**
     * 测试创建草稿时各必填字段与非负时长校验逻辑。
     */
    @Test
    @DisplayName("创建草稿时参数校验失败抛出异常")
    void shouldFailWhenInvalidArguments() {
        // 步骤 1: 验证视频ID为空时的参数检查
        assertThatThrownBy(() -> VideoContent.createDraft(
                "", "cv1", "u1", "title", "desc", "vf", "cf", 100, "tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("视频ID不能为空");

        // 步骤 2: 验证业务编码vid为空时的参数检查
        assertThatThrownBy(() -> VideoContent.createDraft(
                "id1", "", "u1", "title", "desc", "vf", "cf", 100, "tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务编码vid不能为空");

        // 步骤 3: 验证标题为空时的参数检查
        assertThatThrownBy(() -> VideoContent.createDraft(
                "id1", "cv1", "u1", "", "desc", "vf", "cf", 100, "tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("视频标题不能为空");

        // 步骤 4: 验证时长小于0时的参数检查
        assertThatThrownBy(() -> VideoContent.createDraft(
                "id1", "cv1", "u1", "title", "desc", "vf", "cf", -1, "tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("视频时长不能为负数");
    }

    /**
     * 测试从草稿到提交审核、审核通过发布、再到主动下架的完整正向生命周期。
     */
    @Test
    @DisplayName("完整生命周期流转：草稿 -> 提交审核 -> 审核通过发布 -> 下架")
    void shouldFollowFullLifecycle() {
        VideoContent video = createSampleDraft();

        // 步骤 1：提交审核，状态变更为 AUDITING
        video.submitForAudit();
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.AUDITING);

        // 步骤 2：审核通过，状态变更为 PUBLISHED 并记录发布时间戳
        LocalDateTime now = LocalDateTime.now();
        video.publish(now);
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(video.getPublishedAt()).isEqualTo(now);

        // 步骤 3：主动下架，状态变更为 OFFLINE 并留存下架原因
        video.takeOffline("创作者主动下架");
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.OFFLINE);
        assertThat(video.getRejectReason()).isEqualTo("创作者主动下架");
    }

    /**
     * 测试平台管理员介入的违规封禁与恢复解封流转。
     */
    @Test
    @DisplayName("平台封禁与解封流转")
    void shouldHandleBanAndUnban() {
        VideoContent video = createSampleDraft();
        // 步骤 1: 平台管理员执行封禁
        video.ban("内容违规侵权");

        assertThat(video.getStatus()).isEqualTo(CommonStatus.DISABLED);
        assertThat(video.getRejectReason()).isEqualTo("内容违规侵权");

        // 步骤 2: 验证封禁状态下禁止重新提交审核
        assertThatThrownBy(video::submitForAudit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("被封禁/禁用的视频不可提交审核");

        // 步骤 3: 平台解封，恢复 ACTIVE 状态
        video.unban();
        assertThat(video.getStatus()).isEqualTo(CommonStatus.ACTIVE);
    }

    /**
     * 测试机审或人审驳回后，创作者修正元数据并重新提审的业务流转。
     */
    @Test
    @DisplayName("审核被拒与重新提交审核流转")
    void shouldHandleRejectionAndResubmission() {
        VideoContent video = createSampleDraft();
        video.submitForAudit();

        // 步骤 1: 模拟审核拒绝，状态变更为 REJECTED
        video.reject("封面不合规");
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.REJECTED);
        assertThat(video.getRejectReason()).isEqualTo("封面不合规");

        // 步骤 2: 创作者修正元数据并重新提审，驳回原因应被清空
        video.updateMetadata("修正后的标题", null, "f_new_cover", "新标签");
        video.submitForAudit();
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.AUDITING);
        assertThat(video.getRejectReason()).isNull();
    }

    /**
     * 测试防御式状态机：非预期状态跃迁时抛出 IllegalStateException。
     */
    @Test
    @DisplayName("非法状态跃迁抛出 IllegalStateException")
    void shouldThrowOnInvalidStateTransition() {
        VideoContent video = createSampleDraft();

        // 步骤 1: 草稿状态不能跨过审核直接发布
        assertThatThrownBy(() -> video.publish(LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有审核中 (AUDITING)");

        // 步骤 2: 草稿状态不能执行下架
        assertThatThrownBy(() -> video.takeOffline("原因"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有已发布 (PUBLISHED)");
    }

    /**
     * 测试异步同步更新互动数据快照（播放量、点赞、评论、收藏、分享）。
     */
    @Test
    @DisplayName("更新互动快照计数")
    void shouldUpdateMetricsSnapshot() {
        // 步骤 1: 初始化草稿并设置快照计数值
        VideoContent video = createSampleDraft();
        video.updateMetricsSnapshot(1000L, 50L, 10L, 20L, 5L);

        // 步骤 2: 断言快照计数值已正确赋值
        assertThat(video.getViewCount()).isEqualTo(1000L);
        assertThat(video.getLikeCount()).isEqualTo(50L);
        assertThat(video.getCommentCount()).isEqualTo(10L);
        assertThat(video.getStarCount()).isEqualTo(20L);
        assertThat(video.getShareCount()).isEqualTo(5L);
    }

    /**
     * 辅助方法：快速构建一份合法的样例视频草稿实体。
     *
     * @return 初始化的样例草稿聚合根
     */
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
