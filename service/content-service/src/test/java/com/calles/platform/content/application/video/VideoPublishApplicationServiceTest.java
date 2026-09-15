package com.calles.platform.content.application.video;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.content.application.client.FileMetadataDTO;
import com.calles.platform.content.application.client.FileServiceClient;
import com.calles.platform.content.application.security.ContentAccessPolicy;
import com.calles.platform.content.application.tag.ContentTagApplicationService;
import com.calles.platform.content.domain.model.CommonStatus;
import com.calles.platform.content.domain.model.video.PublishStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.exception.ContentException;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxMapper;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxRecord;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import java.sql.Timestamp;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VideoPublishApplicationService 创作者发布工作流应用服务单元测试。
 * <p>
 * 覆盖草稿创建与短码发号、元数据局部更新、提审前 Feign 远程跨服务文件状态校验、主动下架及逻辑删除等核心业务链路。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoPublishApplicationService 视频发布应用服务测试")
class VideoPublishApplicationServiceTest {

    /**
     * 模拟视频仓储接口。
     */
    @Mock
    private VideoContentRepository videoContentRepository;

    /**
     * 模拟内容标签应用服务。
     */
    @Mock
    private ContentTagApplicationService contentTagApplicationService;

    /**
     * 模拟文件服务声明式 Feign 客户端。
     */
    @Mock
    private FileServiceClient fileServiceClient;

    /**
     * 模拟内容访问控制策略。
     */
    @Mock
    private ContentAccessPolicy accessPolicy;

    /**
     * 模拟 Outbox 事件记录持久化 Mapper。
     */
    @Mock
    private ContentOutboxMapper contentOutboxMapper;

    /**
     * 模拟流水线任务协调器。
     */
    @Mock
    private com.calles.platform.content.application.task.VideoTaskCoordinator videoTaskCoordinator;

    /**
     * 被测发布应用服务。
     */
    @InjectMocks
    private VideoPublishApplicationService publishService;


    /**
     * 创作者上下文样例对象。
     */
    private UserInfo sampleUser;

    /**
     * 每个测试用例执行前的环境初始化。
     */
    @BeforeEach
    void setUp() {
        sampleUser = new UserInfo("user_001", "USER", "NORMAL", "session_1", "device_1");
    }

    /**
     * 测试创建草稿正向场景：生成固定 24 位短码、写入仓储并同步维护标签关联热度。
     */
    @Test
    @DisplayName("创建草稿：成功生成 24 位 vid 并持久化与同步标签")
    void shouldCreateDraftSuccessfully() {
        // 步骤 1: 准备创建草稿入参
        VideoRequests.CreateDraft request = new VideoRequests.CreateDraft(
                "微服务架构实战",
                "详细内容简介",
                "file_video_100",
                "file_cover_100",
                600,
                "Java,微服务",
                "PUBLIC"
        );

        // 步骤 2: 执行草稿创建
        String vid = publishService.createDraft("user_001", request);

        // 步骤 3: 验证生成的短码规范（cv开头、24位）
        assertThat(vid).isNotNull();
        assertThat(vid).hasSize(24);
        assertThat(vid).startsWith("cv");

        // 步骤 4: 捕获入库聚合根并验证初始状态与作者归属
        ArgumentCaptor<VideoContent> captor = ArgumentCaptor.forClass(VideoContent.class);
        verify(videoContentRepository).insert(captor.capture());
        VideoContent saved = captor.getValue();
        assertThat(saved.getVid()).isEqualTo(vid);
        assertThat(saved.getAuthorId()).isEqualTo("user_001");
        assertThat(saved.getTitle()).isEqualTo("微服务架构实战");
        assertThat(saved.getPublishStatus()).isEqualTo(PublishStatus.DRAFT);
        assertThat(saved.getStatus()).isEqualTo(CommonStatus.ACTIVE);

        // 步骤 5: 验证触发了标签差量同步
        verify(contentTagApplicationService).syncVideoTags(saved.getId(), "Java,微服务");
    }

    /**
     * 测试创作者修改未发布或已驳回草稿的元数据并差量更新标签。
     */
    @Test
    @DisplayName("修改元数据：草稿状态修改成功并同步标签")
    void shouldUpdateMetadataSuccessfully() {
        // 步骤 1: 准备已有视频记录 Mock
        VideoContent video = VideoContent.createDraft(
                "v_123", "cv10086", "user_001", "原标题", "原简介", "fv", "fc", 300, "原标签"
        );
        when(videoContentRepository.findById("v_123")).thenReturn(Optional.of(video));
        when(videoContentRepository.updateById(any(VideoContent.class))).thenReturn(1);

        VideoRequests.UpdateMetadata request = new VideoRequests.UpdateMetadata(
                "新标题", "新简介", "new_fc", "新标签,微服务"
        );

        // 步骤 2: 执行元数据更新
        publishService.updateMetadata("user_001", "v_123", request);

        // 步骤 3: 断言实体属性被成功更新
        assertThat(video.getTitle()).isEqualTo("新标题");
        assertThat(video.getDescription()).isEqualTo("新简介");
        assertThat(video.getCoverFileId()).isEqualTo("new_fc");
        verify(videoContentRepository).updateById(video);
        verify(contentTagApplicationService).syncVideoTags("v_123", "新标签,微服务");
    }

    /**
     * 测试提交审核流程：调用 file-service 验证视频与封面均就绪后，状态流转为 AUDITING 并发布提审 Outbox 事件。
     */
    @Test
    @DisplayName("提交审核：调用 file-service 校验文件就绪、更新为 AUDITING 并写入 Outbox")
    void shouldSubmitForAuditSuccessfully() {
        // 步骤 1: 准备已有草稿 Mock
        VideoContent video = VideoContent.createDraft(
                "v_123", "cv10086", "user_001", "标题", "简介", "fv_001", "fc_001", 300, "tag"
        );
        when(videoContentRepository.findById("v_123")).thenReturn(Optional.of(video));
        when(videoContentRepository.updateById(any(VideoContent.class))).thenReturn(1);

        // 步骤 2: Mock 视频文件与封面文件均就绪 (CONFIRMED & ACTIVE)
        FileMetadataDTO readyVideo = new FileMetadataDTO("fv_001", "v.mp4", "video/mp4", 1000L, 1000L, "ACTIVE", "CONFIRMED");
        FileMetadataDTO readyCover = new FileMetadataDTO("fc_001", "c.jpg", "image/jpeg", 200L, 200L, "ACTIVE", "CONFIRMED");

        when(fileServiceClient.getFileMetadata(eq("fv_001"), eq("user_001"), eq("USER")))
                .thenReturn(ApiResponse.ok(readyVideo));
        when(fileServiceClient.getFileMetadata(eq("fc_001"), eq("user_001"), eq("USER")))
                .thenReturn(ApiResponse.ok(readyCover));

        // 步骤 3: 执行提审动作
        publishService.submitForAudit("user_001", "v_123");

        // 步骤 4: 校验聚合根变为 AUDITING 且 Outbox 事件已写入
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.AUDITING);
        verify(videoContentRepository).updateById(video);
        verify(contentOutboxMapper).insert(any(ContentOutboxRecord.class), any(Timestamp.class), eq("PENDING"), any(Timestamp.class));
        verify(videoTaskCoordinator).initPipelineTasks("v_123");
    }


    /**
     * 测试提审防御：当源文件在 file-service 尚未就绪（如上传中或未确认）时阻断提审并返回 BAD_REQUEST。
     */
    @Test
    @DisplayName("提交审核：视频文件未就绪时抛出异常")
    void shouldFailSubmitWhenVideoFileNotReady() {
        // 步骤 1: 模拟草稿视频
        VideoContent video = VideoContent.createDraft(
                "v_123", "cv10086", "user_001", "标题", "简介", "fv_001", "fc_001", 300, "tag"
        );
        when(videoContentRepository.findById("v_123")).thenReturn(Optional.of(video));

        // 步骤 2: 模拟远程 file-service 返回 PENDING（未确认完成）
        FileMetadataDTO pendingVideo = new FileMetadataDTO("fv_001", "v.mp4", "video/mp4", 1000L, null, "ACTIVE", "PENDING");
        when(fileServiceClient.getFileMetadata(eq("fv_001"), eq("user_001"), eq("USER")))
                .thenReturn(ApiResponse.ok(pendingVideo));

        // 步骤 3: 断言提审失败并抛出 400 业务异常
        assertThatThrownBy(() -> publishService.submitForAudit("user_001", "v_123"))
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("尚未上传就绪或已失效")
                .extracting(e -> ((ContentException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * 测试创作者主动下架视频流程：状态置为 OFFLINE 并发送下架 Outbox 事件。
     */
    @Test
    @DisplayName("主动下架：状态置为 OFFLINE 并写入 Outbox 事件")
    void shouldTakeOfflineSuccessfully() {
        // 步骤 1: 准备处于已发布状态的视频记录
        VideoContent video = VideoContent.createDraft(
                "v_123", "cv10086", "user_001", "标题", "简介", "fv", "fc", 300, "tag"
        );
        video.submitForAudit();
        video.publish(null);
        when(videoContentRepository.findById("v_123")).thenReturn(Optional.of(video));
        when(videoContentRepository.updateById(any(VideoContent.class))).thenReturn(1);

        // 步骤 2: 执行下架操作
        publishService.takeOffline("user_001", "v_123", "作者主动下架");

        // 步骤 3: 验证下架状态与 Outbox 下架广播
        assertThat(video.getPublishStatus()).isEqualTo(PublishStatus.OFFLINE);
        assertThat(video.getRejectReason()).isEqualTo("作者主动下架");
        verify(contentOutboxMapper).insert(any(ContentOutboxRecord.class), any(Timestamp.class), eq("PENDING"), any(Timestamp.class));
    }

    /**
     * 测试创作者删除视频：逻辑删除视频记录并清空标签引用计数。
     */
    @Test
    @DisplayName("删除视频：逻辑删除并清除标签关联热度")
    void shouldDeleteVideoSuccessfully() {
        // 步骤 1: 准备视频记录
        VideoContent video = VideoContent.createDraft(
                "v_123", "cv10086", "user_001", "标题", "简介", "fv", "fc", 300, "tag"
        );
        when(videoContentRepository.findById("v_123")).thenReturn(Optional.of(video));

        // 步骤 2: 执行删除操作
        publishService.deleteVideo("user_001", "v_123");

        // 步骤 3: 验证仓储删除调用与标签解绑清空
        verify(videoContentRepository).deleteById("v_123");
        verify(contentTagApplicationService).syncVideoTags("v_123", "");
    }
}
