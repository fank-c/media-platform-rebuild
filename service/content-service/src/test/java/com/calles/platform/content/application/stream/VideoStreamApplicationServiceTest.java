package com.calles.platform.content.application.stream;

import com.calles.platform.content.domain.model.stream.StreamCodec;
import com.calles.platform.content.domain.model.stream.StreamFormat;
import com.calles.platform.content.domain.model.stream.StreamQuality;
import com.calles.platform.content.domain.model.stream.TranscodeStatus;
import com.calles.platform.content.domain.model.stream.VideoStream;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.domain.repository.VideoStreamRepository;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VideoStreamApplicationService 多清晰度流媒体应用服务单元测试。
 * <p>
 * 验证转码任务完成或异常时的回调处理，涵盖新规格流媒体记录新增与既有规格流媒体记录的覆盖更新逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoStreamApplicationService 转码流服务测试")
class VideoStreamApplicationServiceTest {

    /**
     * 模拟流媒体仓储接口。
     */
    @Mock
    private VideoStreamRepository videoStreamRepository;

    /**
     * 模拟视频聚合根仓储接口。
     */
    @Mock
    private VideoContentRepository videoContentRepository;

    /**
     * 被测转码流应用服务。
     */
    @InjectMocks
    private VideoStreamApplicationService streamService;

    /**
     * 测试全新转码规格（如首个 1080P MP4）流媒体记录的初始化与入库。
     */
    @Test
    @DisplayName("新规格转码流插入注册成功")
    void shouldRegisterNewStream() {
        // 步骤 1: 模拟前置数据：存在对应视频聚合根，但无此规格流记录
        VideoContent video = VideoContent.createDraft(
                "v_100", "cv10086", "author_1", "标题", "简介", "fv", "fc", 120, "tag"
        );
        when(videoContentRepository.findById("v_100")).thenReturn(Optional.of(video));
        when(videoStreamRepository.findBySpec(eq("v_100"), eq(StreamQuality.P1080), eq(StreamFormat.MP4)))
                .thenReturn(Optional.empty());

        VideoRequests.TranscodeCallback request = new VideoRequests.TranscodeCallback(
                "v_100", "1080P", "MP4", "H264", "f_1080p", 2048000L, 3000, 30, "COMPLETED"
        );

        // 步骤 2: 执行转码回调注册
        streamService.registerStream(request);

        // 步骤 3: 捕获入库流实体并校验属性转换正确性
        ArgumentCaptor<VideoStream> captor = ArgumentCaptor.forClass(VideoStream.class);
        verify(videoStreamRepository).insert(captor.capture());
        VideoStream captured = captor.getValue();
        assertThat(captured.getVideoId()).isEqualTo("v_100");
        assertThat(captured.getQuality()).isEqualTo(StreamQuality.P1080);
        assertThat(captured.getFormat()).isEqualTo(StreamFormat.MP4);
        assertThat(captured.getCodec()).isEqualTo(StreamCodec.H264);
        assertThat(captured.getFileId()).isEqualTo("f_1080p");
        assertThat(captured.getTranscodeStatus()).isEqualTo(TranscodeStatus.COMPLETED);
    }

    /**
     * 测试既有转码规格（如重试转码完成）的文件与状态覆盖更新。
     */
    @Test
    @DisplayName("已存在规格更新文件与状态")
    void shouldUpdateExistingStream() {
        // 步骤 1: 模拟已存在处于 PROCESSING 状态的同规格流媒体记录
        VideoContent video = VideoContent.createDraft(
                "v_100", "cv10086", "author_1", "标题", "简介", "fv", "fc", 120, "tag"
        );
        when(videoContentRepository.findById("v_100")).thenReturn(Optional.of(video));

        VideoStream existing = VideoStream.builder()
                .id("stream_old")
                .videoId("v_100")
                .quality(StreamQuality.P1080)
                .format(StreamFormat.MP4)
                .transcodeStatus(TranscodeStatus.PROCESSING)
                .build();
        when(videoStreamRepository.findBySpec(eq("v_100"), eq(StreamQuality.P1080), eq(StreamFormat.MP4)))
                .thenReturn(Optional.of(existing));

        VideoRequests.TranscodeCallback request = new VideoRequests.TranscodeCallback(
                "v_100", "1080P", "MP4", "H264", "f_1080p_new", 3000000L, 3500, 30, "COMPLETED"
        );

        // 步骤 2: 执行转码回调注册
        streamService.registerStream(request);

        // 步骤 3: 验证已有实体已被就地更新，无需新记录插入
        assertThat(existing.getFileId()).isEqualTo("f_1080p_new");
        assertThat(existing.getFileSize()).isEqualTo(3000000L);
        assertThat(existing.getTranscodeStatus()).isEqualTo(TranscodeStatus.COMPLETED);
    }
}
