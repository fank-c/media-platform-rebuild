package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.application.service.CandidateVideoApplicationService;
import com.calles.platform.recommend.config.RecommendMessagingConfiguration;
import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 下架契约回归：实际绑定必须接收内容服务路由，并驱动幂等候选清退。 */
class VideoOfflineContractTest {
    /** 错误的绑定键会阻断内容服务下架事件送达。 */
    @Test
    void bindsContentOfflineRoute() {
        var configuration = new RecommendMessagingConfiguration();
        var binding = configuration.recommendVideoOfflineBinding(
                configuration.recommendVideoLifecycleQueue(), configuration.recommendMediaEventsExchange());
        assertThat(binding.getRoutingKey()).isEqualTo("content.video.offline");
        assertThat(binding.getDestination()).isEqualTo("recommend-service.video-lifecycle.v1");
        assertThat(binding.getExchange()).isEqualTo("media.platform.events");
    }

    /** 保留真实消费者和应用服务，仅用内存状态替代数据库写入。 */
    @Test
    void offlineEventRemovesCandidateAndIsIdempotent() {
        var status = new AtomicReference<>(CandidateStatus.ACTIVE);
        var repository = mock(CandidateVideoRepository.class);
        when(repository.updateStatusByVideoId(eq("video_test"), eq(CandidateStatus.OFFLINE)))
                .thenAnswer(invocation -> {
                    status.set(invocation.getArgument(1));
                    return 1;
                });
        var consumer = new VideoLifecycleConsumer(new CandidateVideoApplicationService(repository), new ObjectMapper());
        String payload = """
                {"eventId":"offline_test","eventType":"content.video.offline",
                 "videoId":"video_test","vid":"cv_test","authorId":"author_test"}
                """;
        consumer.onVideoLifecycleEvent(payload);
        assertThat(status.get()).isEqualTo(CandidateStatus.OFFLINE);
        consumer.onVideoLifecycleEvent(payload);
        assertThat(status.get()).isEqualTo(CandidateStatus.OFFLINE);
    }
}
