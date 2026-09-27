package com.calles.platform.interaction.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.interaction.domain.model.like.LikeStatus;
import com.calles.platform.interaction.domain.model.like.VideoLike;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoLikePO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoLikeMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 视频点赞仓储 MyBatis-Plus 实现单元测试。
 */
@ExtendWith(MockitoExtension.class)
class VideoLikeRepositoryImplTest {

    @Mock
    private VideoLikeMapper mapper;

    private VideoLikeRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new VideoLikeRepositoryImpl(mapper);
    }

    @Test
    @DisplayName("findActivePageByUserId 用户有效点赞分页查询转换正确")
    void shouldFindActivePageByUserId() {
        VideoLikePO samplePo = VideoLikePO.builder()
                .id("like_001")
                .vid("cv_100")
                .userId("user_001")
                .status(LikeStatus.ACTIVE.getValue())
                .version(1L)
                .deleted(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(mapper.selectList(any())).thenReturn(List.of(samplePo));

        List<VideoLike> result = repository.findActivePageByUserId("user_001", 0, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("like_001");
        assertThat(result.get(0).getVid()).isEqualTo("cv_100");
        assertThat(result.get(0).getStatus()).isEqualTo(LikeStatus.ACTIVE);
    }

    @Test
    @DisplayName("findActivePageByUserId 用户 ID 为空时直接返回空列表")
    void shouldReturnEmptyWhenUserIdIsBlank() {
        assertThat(repository.findActivePageByUserId(null, 0, 20)).isEmpty();
        assertThat(repository.findActivePageByUserId("", 0, 20)).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("findActivePageByUserId 偏移量为负数或条数非正时直接返回空列表")
    void shouldReturnEmptyWhenOffsetOrLimitIsInvalid() {
        assertThat(repository.findActivePageByUserId("user_001", -1, 20)).isEmpty();
        assertThat(repository.findActivePageByUserId("user_001", 0, 0)).isEmpty();
        assertThat(repository.findActivePageByUserId("user_001", 0, -10)).isEmpty();
        verifyNoInteractions(mapper);
    }
}
