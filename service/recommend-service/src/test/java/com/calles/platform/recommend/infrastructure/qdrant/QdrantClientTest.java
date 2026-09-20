package com.calles.platform.recommend.infrastructure.qdrant;

import com.calles.platform.recommend.config.QdrantProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qdrant 客户端单元测试。
 */
class QdrantClientTest {

    @Test
    @DisplayName("验证 32 位 UUID 规整为 36 位标准连字符 UUID")
    void shouldFormatToStandardUuid() {
        String raw32 = "c1f2e3d4e5f6a7b8c9d0e1f2a3b4c5d6";
        String formatted = QdrantClient.formatToStandardUuid(raw32);

        assertThat(formatted).isEqualTo("c1f2e3d4-e5f6-a7b8-c9d0-e1f2a3b4c5d6");
        assertThat(formatted).hasSize(36);

        // 如果已经是 36 位，保持原样
        assertThat(QdrantClient.formatToStandardUuid(formatted)).isEqualTo(formatted);
    }

    @Test
    @DisplayName("Qdrant 配置关闭时跳过同步并返回 false")
    void shouldReturnFalseWhenDisabled() {
        QdrantProperties properties = new QdrantProperties();
        properties.setEnabled(false);
        QdrantClient client = new QdrantClient(properties);

        boolean synced = client.upsertPoint("test_col", "123", List.of(0.1f), Map.of());
        assertThat(synced).isFalse();
    }

    @Test
    @DisplayName("Qdrant 网络不通或未启动时安全返回 false 不打崩业务")
    void shouldHandleNetworkFailureGracefully() {
        QdrantProperties properties = new QdrantProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(59999); // 不存在的端口
        properties.setTimeoutMs(500);

        QdrantClient client = new QdrantClient(properties);
        boolean synced = client.upsertPoint("test_col", "c1f2e3d4e5f6a7b8c9d0e1f2a3b4c5d6", List.of(0.1f, 0.2f), Map.of());

        assertThat(synced).isFalse();
    }
}
