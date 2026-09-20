package com.calles.platform.recommend.infrastructure.engine;

import com.calles.platform.recommend.domain.engine.VectorEmbeddingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 向量引擎策略路由器单元测试。
 */
@ExtendWith(MockitoExtension.class)
class VectorEmbeddingEngineRouterTest {

    @Mock
    private VectorEmbeddingEngine remoteEngine;

    @Mock
    private VectorEmbeddingEngine localEngine;

    private VectorEmbeddingEngineRouter router;

    @BeforeEach
    void setUp() {
        when(remoteEngine.getEngineType()).thenReturn("remote");
        when(localEngine.getEngineType()).thenReturn("local");
        router = new VectorEmbeddingEngineRouter(List.of(remoteEngine, localEngine));
    }

    @Test
    @DisplayName("精准路由到 remote 引擎")
    void shouldRouteToRemoteEngine() {
        VectorEmbeddingEngine result = router.route("remote");
        assertThat(result).isSameAs(remoteEngine);
    }

    @Test
    @DisplayName("大小写无关精准路由到 local 引擎")
    void shouldRouteToLocalEngineCaseInsensitive() {
        VectorEmbeddingEngine result = router.route("LOCAL");
        assertThat(result).isSameAs(localEngine);
    }

    @Test
    @DisplayName("类型为空时抛出参数异常")
    void shouldThrowExceptionWhenTypeIsBlank() {
        assertThatThrownBy(() -> router.route(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("未注册类型时抛出明确不支持异常")
    void shouldThrowExceptionWhenTypeNotFound() {
        assertThatThrownBy(() -> router.route("unknown-type"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的向量化引擎类型: unknown-type");
    }

    @Test
    @DisplayName("supports 方法正确判定注册状态")
    void shouldCheckSupportStatusCorrectly() {
        assertThat(router.supports("remote")).isTrue();
        assertThat(router.supports("local")).isTrue();
        assertThat(router.supports("onnx")).isFalse();
        assertThat(router.supports(null)).isFalse();
    }
}
