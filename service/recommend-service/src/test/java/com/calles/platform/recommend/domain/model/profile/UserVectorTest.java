package com.calles.platform.recommend.domain.model.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UserVector 用户向量值对象与 EMA 算法测试。
 */
@DisplayName("UserVector 用户向量值对象单元测试")
class UserVectorTest {

    @Test
    @DisplayName("empty：冷启动空向量判定")
    void shouldRecognizeEmptyVector() {
        UserVector emptyVec = UserVector.empty(512);
        assertThat(emptyVec.isEmpty()).isTrue();
        assertThat(emptyVec.getDimension()).isEqualTo(512);
        assertThat(emptyVec.getVector()).isEmpty();
    }

    @Test
    @DisplayName("l2Normalize：正常执行 L2 模长归一化")
    void shouldL2NormalizeCorrectly() {
        // 3D 向量 (3, 4, 0)，模长 sqrt(9 + 16) = 5
        List<Float> raw = List.of(3.0f, 4.0f, 0.0f);
        List<Float> normalized = UserVector.l2Normalize(raw);

        assertThat(normalized).hasSize(3);
        assertThat(normalized.get(0)).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(normalized.get(1)).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(normalized.get(2)).isCloseTo(0.0f, org.assertj.core.data.Offset.offset(0.001f));

        // 验证归一化后模长为 1.0
        double normSq = normalized.stream().mapToDouble(v -> v * v).sum();
        assertThat(normSq).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("applyEma：冷启动首视频直接作为初始化向量并归一化")
    void shouldInitializeOnFirstConsumption() {
        UserVector emptyVec = UserVector.empty(2);
        List<Float> firstVideo = List.of(0.0f, 10.0f);

        UserVector updated = emptyVec.applyEma(firstVideo, 0.2);

        assertThat(updated.isEmpty()).isFalse();
        assertThat(updated.getVector()).hasSize(2);
        assertThat(updated.getVector().get(0)).isEqualTo(0.0f);
        assertThat(updated.getVector().get(1)).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("applyEma：后续视频平滑合入计算并保证尺度归一")
    void shouldSmoothlyMergeViaEma() {
        // 用户初始向量 (1, 0)，新消费视频向量 (0, 1)
        UserVector initial = UserVector.of(List.of(1.0f, 0.0f));
        List<Float> newVideo = List.of(0.0f, 1.0f);

        // alpha = 0.2 -> (0.8 * 1 + 0.2 * 0, 0.8 * 0 + 0.2 * 1) = (0.8, 0.2)
        // 归一化后: norm = sqrt(0.64 + 0.04) = sqrt(0.68) ≈ 0.8246
        UserVector updated = initial.applyEma(newVideo, 0.2);

        assertThat(updated.getVector()).hasSize(2);
        float v0 = updated.getVector().get(0);
        float v1 = updated.getVector().get(1);

        assertThat(v0).isGreaterThan(v1); // 历史偏好 (0.8) 占主导
        assertThat(v1).isGreaterThan(0.0f); // 吸收了新兴趣 (0.2)
        assertThat(v0 * v0 + v1 * v1).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.001f));
    }

    @Test
    @DisplayName("applyEma：特征维度不匹配时抛出明确异常")
    void shouldThrowWhenDimensionMismatch() {
        UserVector initial = UserVector.of(List.of(1.0f, 0.0f, 0.0f));
        List<Float> mismatched = List.of(1.0f, 0.0f);

        assertThatThrownBy(() -> initial.applyEma(mismatched, 0.2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("向量维度不匹配");
    }
}
