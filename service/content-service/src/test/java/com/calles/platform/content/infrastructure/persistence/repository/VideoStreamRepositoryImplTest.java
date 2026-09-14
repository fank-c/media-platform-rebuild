package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.stream.StreamCodec;
import com.calles.platform.content.domain.model.stream.StreamFormat;
import com.calles.platform.content.domain.model.stream.StreamQuality;
import com.calles.platform.content.domain.model.stream.TranscodeStatus;
import com.calles.platform.content.domain.model.stream.VideoStream;
import com.calles.platform.content.infrastructure.persistence.entity.VideoStreamPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoStreamMapper;
import java.util.List;
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
 * VideoStreamRepositoryImpl 流媒体仓储实现单元测试。
 * <p>
 * 验证 VideoStreamMapper 交互，包括转码流插入与主键回填、按视频主键查询全部清晰度流、按分辨率与格式复合规格检索以及级联清理删除等场景。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoStreamRepositoryImpl 仓储实现测试")
class VideoStreamRepositoryImplTest {

    /**
     * 模拟 MyBatis-Plus 流媒体持久化 Mapper。
     */
    @Mock
    private VideoStreamMapper mapper;

    /**
     * 被测流媒体仓储实现。
     */
    @InjectMocks
    private VideoStreamRepositoryImpl repository;

    /**
     * 测试新增流媒体记录并正确回填生成的 UUID 主键。
     */
    @Test
    @DisplayName("插入流规格记录并回填主键")
    void shouldInsertStream() {
        // 步骤 1: 准备实体与 Mapper 插桩
        VideoStream stream = VideoStream.builder()
                .id("stream_uuid_001")
                .videoId("vid_100")
                .quality(StreamQuality.P1080)
                .format(StreamFormat.MP4)
                .codec(StreamCodec.H264)
                .fileId("file_1080p")
                .fileSize(1024000L)
                .transcodeStatus(TranscodeStatus.COMPLETED)
                .build();

        when(mapper.insert(any(VideoStreamPO.class))).thenAnswer(invocation -> {
            VideoStreamPO po = invocation.getArgument(0);
            po.setId("stream_uuid_001");
            return 1;
        });

        // 步骤 2: 调用仓储插入方法
        int rows = repository.insert(stream);

        // 步骤 3: 验证影响行数及主键值回填
        assertThat(rows).isEqualTo(1);
        assertThat(stream.getId()).isEqualTo("stream_uuid_001");
    }

    /**
     * 测试根据所属视频 ID 获取关联的多清晰度流列表。
     */
    @Test
    @DisplayName("按视频ID查询多分辨率流列表")
    void shouldFindByVideoId() {
        // 步骤 1: 准备 1080P 和 720P 两个不同清晰度流的 PO 列表
        VideoStreamPO po1 = VideoStreamPO.builder()
                .id("stream_1")
                .videoId("vid_100")
                .quality("1080P")
                .format("MP4")
                .codec("H264")
                .fileId("f1")
                .fileSize(2000L)
                .transcodeStatus("COMPLETED")
                .build();
        VideoStreamPO po2 = VideoStreamPO.builder()
                .id("stream_2")
                .videoId("vid_100")
                .quality("720P")
                .format("MP4")
                .codec("H264")
                .fileId("f2")
                .fileSize(1000L)
                .transcodeStatus("COMPLETED")
                .build();

        when(mapper.selectByVideoId("vid_100")).thenReturn(List.of(po1, po2));

        // 步骤 2: 调用仓储按视频ID查询
        List<VideoStream> streams = repository.findByVideoId("vid_100");

        // 步骤 3: 验证列表条数与枚举还原正确性
        assertThat(streams).hasSize(2);
        assertThat(streams.get(0).getQuality()).isEqualTo(StreamQuality.P1080);
        assertThat(streams.get(0).getTranscodeStatus()).isEqualTo(TranscodeStatus.COMPLETED);
        assertThat(streams.get(1).getQuality()).isEqualTo(StreamQuality.P720);
    }

    /**
     * 测试根据视频ID、清晰度与格式组合精确检索特定单条转码流。
     */
    @Test
    @DisplayName("按规格精准查询单条流")
    void shouldFindBySpec() {
        // 步骤 1: 准备 4K HLS 的 PO 插桩
        VideoStreamPO po = VideoStreamPO.builder()
                .id("stream_1")
                .videoId("vid_100")
                .quality("4K")
                .format("HLS")
                .codec("H265")
                .fileId("f_4k")
                .transcodeStatus("COMPLETED")
                .build();

        when(mapper.selectBySpec("vid_100", "4K", "HLS")).thenReturn(po);

        // 步骤 2: 调用按规格查询
        Optional<VideoStream> found = repository.findBySpec("vid_100", StreamQuality.P4K, StreamFormat.HLS);

        // 步骤 3: 验证单条流存在且枚举属性转换无误
        assertThat(found).isPresent();
        assertThat(found.get().getQuality()).isEqualTo(StreamQuality.P4K);
        assertThat(found.get().getFormat()).isEqualTo(StreamFormat.HLS);
    }

    /**
     * 测试根据所属视频 ID 批量物理/逻辑删除关联流媒体切片记录。
     */
    @Test
    @DisplayName("根据视频ID删除关联流记录")
    void shouldDeleteByVideoId() {
        // 步骤 1: 模拟批量删除影响行数
        when(mapper.deleteByVideoId("vid_100")).thenReturn(2);

        // 步骤 2: 调用仓储删除
        int rows = repository.deleteByVideoId("vid_100");

        // 步骤 3: 校验影响行数与 Mapper 调用
        assertThat(rows).isEqualTo(2);
        verify(mapper).deleteByVideoId(eq("vid_100"));
    }
}
