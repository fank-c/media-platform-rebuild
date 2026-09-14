package com.calles.platform.content.application.video;

import com.calles.platform.content.domain.model.CommonStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxMapper;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxRecord;
import java.sql.Timestamp;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VideoModerationApplicationService 平台违规治理应用服务单元测试。
 * <p>
 * 覆盖管理后台管理员介入的主动封禁（禁用下架、留存违规缘由并发送 Outbox 广播）与误判解封恢复等关键风控用例。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoModerationApplicationService 平台风控治理测试")
class VideoModerationApplicationServiceTest {

    /**
     * 模拟视频仓储接口。
     */
    @Mock
    private VideoContentRepository videoContentRepository;

    /**
     * 模拟 Outbox 事件记录 Mapper。
     */
    @Mock
    private ContentOutboxMapper contentOutboxMapper;

    /**
     * 被测治理应用服务。
     */
    @InjectMocks
    private VideoModerationApplicationService moderationService;

    /**
     * 测试管理员封禁违规视频，验证状态流转为 DISABLED、记录封禁原因并投递 Outbox 事件。
     */
    @Test
    @DisplayName("封禁视频：状态变为 DISABLED，记录原因并发送 Outbox 事件")
    void shouldBanVideoSuccessfully() {
        // 步骤 1: 模拟已存在的视频内容
        VideoContent video = VideoContent.createDraft(
                "v_100", "cv10086", "author_1", "标题", "简介", "fv", "fc", 120, "tag"
        );
        when(videoContentRepository.findById("v_100")).thenReturn(Optional.of(video));
        when(videoContentRepository.updateById(video)).thenReturn(1);

        // 步骤 2: 执行封禁操作
        moderationService.banVideo("admin_01", "v_100", "违规涉政内容");

        // 步骤 3: 验证聚合根状态与驳回原因
        assertThat(video.getStatus()).isEqualTo(CommonStatus.DISABLED);
        assertThat(video.getRejectReason()).isEqualTo("违规涉政内容");

        // 步骤 4: 验证 Outbox 广播与仓储持久化
        verify(contentOutboxMapper).insert(any(ContentOutboxRecord.class), any(Timestamp.class), eq("PENDING"), any(Timestamp.class));
        verify(videoContentRepository).updateById(video);
    }

    /**
     * 测试管理员解封视频，验证状态重置恢复为 ACTIVE 并可靠发出恢复事件通知下游。
     */
    @Test
    @DisplayName("解封视频：状态恢复为 ACTIVE 并发送 Outbox 事件")
    void shouldUnbanVideoSuccessfully() {
        // 步骤 1: 模拟处于已封禁状态的视频
        VideoContent video = VideoContent.createDraft(
                "v_100", "cv10086", "author_1", "标题", "简介", "fv", "fc", 120, "tag"
        );
        video.ban("原因");
        when(videoContentRepository.findById("v_100")).thenReturn(Optional.of(video));
        when(videoContentRepository.updateById(video)).thenReturn(1);

        // 步骤 2: 执行解封操作
        moderationService.unbanVideo("admin_01", "v_100");

        // 步骤 3: 验证状态恢复为正常 ACTIVE
        assertThat(video.getStatus()).isEqualTo(CommonStatus.ACTIVE);

        // 步骤 4: 验证 Outbox 事件记录与仓储持久化
        verify(contentOutboxMapper).insert(any(ContentOutboxRecord.class), any(Timestamp.class), eq("PENDING"), any(Timestamp.class));
        verify(videoContentRepository).updateById(video);
    }
}
