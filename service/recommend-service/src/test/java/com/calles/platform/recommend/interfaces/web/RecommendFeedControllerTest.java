package com.calles.platform.recommend.interfaces.web;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.recommend.application.dto.RecommendFeedResult;
import com.calles.platform.recommend.application.dto.RecommendItemResult;
import com.calles.platform.recommend.application.service.FeedbackApplicationService;
import com.calles.platform.recommend.application.service.RecommendFeedBufferService;
import com.calles.platform.recommend.application.service.UserBlockApplicationService;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import com.calles.platform.recommend.interfaces.web.dto.FeedbackSubmitRequest;
import com.calles.platform.recommend.interfaces.web.dto.RecommendFeedResponse;
import com.calles.platform.recommend.interfaces.web.dto.UserBlockRequest;
import com.calles.platform.recommend.interfaces.web.dto.UserBlockResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RecommendFeedController 推荐控制器单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendFeedController Web 控制器测试")
class RecommendFeedControllerTest {

    @Mock
    private RecommendFeedBufferService recommendFeedBufferService;
    @Mock
    private FeedbackApplicationService feedbackApplicationService;
    @Mock
    private UserBlockApplicationService userBlockApplicationService;

    @InjectMocks
    private RecommendFeedController controller;

    @Test
    @DisplayName("GET /api/recommend/feed：正常获取推荐列表，透传参数并组装响应")
    void shouldGetFeedSuccessfully() {
        String userId = "user_feed_01";
        List<RecommendItemResult> items = List.of(
                new RecommendItemResult("vid_101", 0.95, "VECTOR", "为您精选"),
                new RecommendItemResult("vid_102", 0.88, "COLD_START", "新鲜发布")
        );
        when(recommendFeedBufferService.consumeFeed(userId, 10))
                .thenReturn(new RecommendFeedResult(items, true));

        ApiResponse<RecommendFeedResponse> response = controller.getFeed(userId, 10);

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.data().getItems()).hasSize(2);
        assertThat(response.data().getItems().get(0).getVid()).isEqualTo("vid_101");
        assertThat(response.data().getHasMore()).isTrue();
    }

    @Test
    @DisplayName("POST /api/recommend/feedback：正常上报行为反馈流水")
    void shouldSubmitFeedbackSuccessfully() {
        FeedbackSubmitRequest request = new FeedbackSubmitRequest(
                "vid_fb_01", "PLAY", 15, 30, null, LocalDateTime.now()
        );

        ApiResponse<Void> response = controller.submitFeedback("user_fb_01", "trace_001", request);

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(200);
        verify(feedbackApplicationService).recordFeedback(
                eq("user_fb_01"), eq("vid_fb_01"), eq(FeedbackActionType.PLAY),
                eq(15), eq(30), isNull(), eq("trace_001"), any()
        );
    }

    @Test
    @DisplayName("POST /api/recommend/blocks：登录态添加屏蔽记录成功")
    void shouldAddBlockSuccessfully() {
        UserBlockRequest request = new UserBlockRequest("AUTHOR", "author_bad", "不喜欢该作者");
        UserBlock block = UserBlock.create("user_b_01", BlockType.AUTHOR, "author_bad", "不喜欢该作者");
        when(userBlockApplicationService.addBlock("user_b_01", BlockType.AUTHOR, "author_bad", "不喜欢该作者"))
                .thenReturn(block);

        ApiResponse<UserBlockResponse> response = controller.addBlock("user_b_01", request);

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.data().getBlockType()).isEqualTo("AUTHOR");
        assertThat(response.data().getTargetId()).isEqualTo("author_bad");
    }

    @Test
    @DisplayName("POST /api/recommend/blocks：未登录时拒绝操作")
    void shouldRejectAddBlockWhenNotLoggedIn() {
        UserBlockRequest request = new UserBlockRequest("VIDEO", "vid_01", "不喜欢");

        assertThatThrownBy(() -> controller.addBlock(null, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前操作需要登录身份");
    }

    @Test
    @DisplayName("DELETE /api/recommend/blocks：撤销屏蔽成功")
    void shouldRemoveBlockSuccessfully() {
        ApiResponse<Void> response = controller.removeBlock("user_b_01", "VIDEO", "vid_remove");

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(200);
        verify(userBlockApplicationService).removeBlock("user_b_01", BlockType.VIDEO, "vid_remove");
    }

    @Test
    @DisplayName("GET /api/recommend/blocks：列出用户当前屏蔽列表")
    void shouldListBlocksSuccessfully() {
        UserBlock block = UserBlock.create("user_b_01", BlockType.TOPIC, "topic_01", "不感冒");
        when(userBlockApplicationService.listBlocks("user_b_01")).thenReturn(List.of(block));

        ApiResponse<List<UserBlockResponse>> response = controller.listBlocks("user_b_01");

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).getTargetId()).isEqualTo("topic_01");
    }
}
