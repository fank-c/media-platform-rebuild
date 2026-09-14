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

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoStreamRepositoryImpl 仓储实现测试")
class VideoStreamRepositoryImplTest {

    @Mock
    private VideoStreamMapper mapper;

    @InjectMocks
    private VideoStreamRepositoryImpl repository;

    @Test
    @DisplayName("插入流规格记录并回填主键")
    void shouldInsertStream() {
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

        int rows = repository.insert(stream);

        assertThat(rows).isEqualTo(1);
        assertThat(stream.getId()).isEqualTo("stream_uuid_001");
    }

    @Test
    @DisplayName("按视频ID查询多分辨率流列表")
    void shouldFindByVideoId() {
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

        List<VideoStream> streams = repository.findByVideoId("vid_100");

        assertThat(streams).hasSize(2);
        assertThat(streams.get(0).getQuality()).isEqualTo(StreamQuality.P1080);
        assertThat(streams.get(0).getTranscodeStatus()).isEqualTo(TranscodeStatus.COMPLETED);
        assertThat(streams.get(1).getQuality()).isEqualTo(StreamQuality.P720);
    }

    @Test
    @DisplayName("按规格精准查询单条流")
    void shouldFindBySpec() {
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

        Optional<VideoStream> found = repository.findBySpec("vid_100", StreamQuality.P4K, StreamFormat.HLS);

        assertThat(found).isPresent();
        assertThat(found.get().getQuality()).isEqualTo(StreamQuality.P4K);
        assertThat(found.get().getFormat()).isEqualTo(StreamFormat.HLS);
    }

    @Test
    @DisplayName("根据视频ID删除关联流记录")
    void shouldDeleteByVideoId() {
        when(mapper.deleteByVideoId("vid_100")).thenReturn(2);

        int rows = repository.deleteByVideoId("vid_100");

        assertThat(rows).isEqualTo(2);
        verify(mapper).deleteByVideoId(eq("vid_100"));
    }
}
