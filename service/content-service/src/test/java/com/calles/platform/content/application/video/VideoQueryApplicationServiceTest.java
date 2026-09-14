package com.calles.platform.content.application.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.calles.platform.content.application.tag.ContentTagApplicationService;
import com.calles.platform.content.domain.model.stream.StreamCodec;
import com.calles.platform.content.domain.model.stream.StreamFormat;
import com.calles.platform.content.domain.model.stream.StreamQuality;
import com.calles.platform.content.domain.model.stream.TranscodeStatus;
import com.calles.platform.content.domain.model.stream.VideoStream;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.domain.repository.VideoStreamRepository;
import com.calles.platform.content.exception.ContentException;
import com.calles.platform.content.infrastructure.persistence.entity.VideoContentPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoContentMapper;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * VideoQueryApplicationService 视频 CQRS 读模型检索应用服务单元测试。
 * <p>
 * 覆盖前台已发布视频详情查询、草稿权限门禁控制（非作者/非管理员 404 防御）、多清晰度转码就绪流过滤以及创作者工作台分页检索等场景。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoQueryApplicationService 视频查询服务测试")
class VideoQueryApplicationServiceTest {

    /**
     * 模拟视频聚合根仓储接口。
     */
    @Mock
    private VideoContentRepository videoContentRepository;

    /**
     * 模拟流媒体仓储接口。
     */
    @Mock
    private VideoStreamRepository videoStreamRepository;

    /**
     * 模拟内容标签应用服务。
     */
    @Mock
    private ContentTagApplicationService contentTagApplicationService;

    /**
     * 模拟 MyBatis-Plus 视频持久化 Mapper（支持分页）。
     */
    @Mock
    private VideoContentMapper videoContentMapper;

    /**
     * 被测视频查询应用服务。
     */
    @InjectMocks
    private VideoQueryApplicationService queryService;

    /**
     * 测试前台公开查询已发布且正常状态的视频详情。
     */
    @Test
    @DisplayName("前台查询已发布视频详情成功")
    void shouldGetVideoDetailSuccessfully() {
        // 步骤 1: 准备已发布状态的视频聚合根 Mock
        VideoContent video = VideoContent.createDraft(
                "v_001", "cv_test_001", "author_1", "标题", "简介", "fv", "fc", 120, "Java"
        );
        video.submitForAudit();
        video.publish(LocalDateTime.now());

        when(videoContentRepository.findByVid("cv_test_001")).thenReturn(Optional.of(video));
        when(contentTagApplicationService.getTagNamesByVideoId("v_001")).thenReturn(List.of("Java", "微服务"));

        // 步骤 2: 执行游客前台详情查询
        VideoResponses.Detail detail = queryService.getVideoDetail("cv_test_001", null, false);

        // 步骤 3: 验证出参 DTO 字段拼装与标签关联列表
        assertThat(detail.id()).isEqualTo("v_001");
        assertThat(detail.vid()).isEqualTo("cv_test_001");
        assertThat(detail.title()).isEqualTo("标题");
        assertThat(detail.publishStatus()).isEqualTo("PUBLISHED");
        assertThat(detail.tags()).containsExactly("Java", "微服务");
    }

    /**
     * 测试权限防御：非作者本人且非平台管理员查询未发布的草稿视频时抛出 404 NOT_FOUND。
     */
    @Test
    @DisplayName("非作者且非管理员查询未发布草稿视频时抛出 404")
    void shouldThrowWhenNonOwnerQueriesDraft() {
        // 步骤 1: 准备处于草稿状态的视频聚合根 Mock
        VideoContent video = VideoContent.createDraft(
                "v_001", "cv_test_001", "author_1", "草稿标题", "简介", "fv", "fc", 120, "Java"
        );
        when(videoContentRepository.findByVid("cv_test_001")).thenReturn(Optional.of(video));

        // 步骤 2: 验证第三方用户访问草稿时抛出 404 业务异常
        assertThatThrownBy(() -> queryService.getVideoDetail("cv_test_001", "other_user", false))
                .isInstanceOf(ContentException.class)
                .extracting(e -> ((ContentException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * 测试权限放行：作者本人查询自己未发布的草稿视频详情成功。
     */
    @Test
    @DisplayName("作者本人可以查询自己的草稿视频详情")
    void shouldAllowOwnerToQueryDraft() {
        // 步骤 1: 准备草稿视频 Mock
        VideoContent video = VideoContent.createDraft(
                "v_001", "cv_test_001", "author_1", "草稿标题", "简介", "fv", "fc", 120, "Java"
        );
        when(videoContentRepository.findByVid("cv_test_001")).thenReturn(Optional.of(video));
        when(contentTagApplicationService.getTagNamesByVideoId("v_001")).thenReturn(List.of("Java"));

        // 步骤 2: 作者本人查询
        VideoResponses.Detail detail = queryService.getVideoDetail("cv_test_001", "author_1", false);

        // 步骤 3: 验证正常返回草稿详情
        assertThat(detail.id()).isEqualTo("v_001");
        assertThat(detail.publishStatus()).isEqualTo("DRAFT");
    }

    /**
     * 测试查询播放流列表：仅过滤并返回 transcodeStatus 为 COMPLETED 的可用清晰度流。
     */
    @Test
    @DisplayName("查询播放流：仅过滤并返回转码状态为 COMPLETED 的流")
    void shouldGetOnlyCompletedStreams() {
        // 步骤 1: 准备已发布的视频记录 Mock
        VideoContent video = VideoContent.createDraft(
                "v_001", "cv_test_001", "author_1", "标题", "简介", "fv", "fc", 120, "Java"
        );
        video.submitForAudit();
        video.publish(LocalDateTime.now());
        when(videoContentRepository.findByVid("cv_test_001")).thenReturn(Optional.of(video));

        // 步骤 2: 模拟一条 COMPLETED（1080P）和一条仍在 PROCESSING（4K）的流
        VideoStream s1 = VideoStream.builder()
                .quality(StreamQuality.P1080)
                .format(StreamFormat.MP4)
                .codec(StreamCodec.H264)
                .fileId("f_1080")
                .fileSize(2000L)
                .transcodeStatus(TranscodeStatus.COMPLETED)
                .build();
        VideoStream s2 = VideoStream.builder()
                .quality(StreamQuality.P4K)
                .format(StreamFormat.HLS)
                .codec(StreamCodec.H265)
                .fileId("f_4k")
                .fileSize(8000L)
                .transcodeStatus(TranscodeStatus.PROCESSING)
                .build();

        when(videoStreamRepository.findByVideoId("v_001")).thenReturn(List.of(s1, s2));

        // 步骤 3: 执行播放流查询
        VideoResponses.PlayStreams playStreams = queryService.getPlayStreams("cv_test_001", null, false);

        // 步骤 4: 验证仅返回就绪的 1080P，未完成的 4K 被正确过滤掉
        assertThat(playStreams.vid()).isEqualTo("cv_test_001");
        assertThat(playStreams.streams()).hasSize(1);
        assertThat(playStreams.streams().get(0).quality()).isEqualTo("1080P");
    }

    /**
     * 测试创作者在个人工作台分页查询作品列表，验证 MyBatis-Plus 分页调用。
     */
    @Test
    @DisplayName("创作者查询本人视频列表调用分页 Mapper")
    void shouldListMyVideos() {
        // 步骤 1: 模拟分页数据构建
        VideoContentPO po = VideoContentPO.builder()
                .id("v1")
                .vid("cv1")
                .title("视频1")
                .duration(120)
                .status("ACTIVE")
                .publishStatus("PUBLISHED")
                .build();
        Page<VideoContentPO> resultPage = new Page<>(1, 10, 1);
        resultPage.setRecords(List.of(po));

        when(videoContentMapper.selectPage(any(), any(LambdaQueryWrapper.class))).thenReturn(resultPage);

        // 步骤 2: 执行创作者作品列表查询
        VideoResponses.CreatorPage page = queryService.listMyVideos("author_1", "PUBLISHED", 1, 10);

        // 步骤 3: 验证分页总条数与记录内容
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).vid()).isEqualTo("cv1");
    }
}
