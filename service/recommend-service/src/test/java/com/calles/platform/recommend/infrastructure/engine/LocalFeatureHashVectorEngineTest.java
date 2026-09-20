package com.calles.platform.recommend.infrastructure.engine;

import com.calles.platform.recommend.config.RecommendEmbeddingProperties;
import com.calles.platform.recommend.domain.engine.model.EmbeddingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * 本地确定性特征散列向量引擎单元测试。
 */
class LocalFeatureHashVectorEngineTest {

    private LocalFeatureHashVectorEngine engine;

    @BeforeEach
    void setUp() {
        RecommendEmbeddingProperties properties = new RecommendEmbeddingProperties();
        properties.getLocal().setDimension(128);
        engine = new LocalFeatureHashVectorEngine(properties);
    }

    @Test
    @DisplayName("验证向量维度与模型标识正确")
    void shouldReturnCorrectDimensionAndModelName() {
        assertThat(engine.getModelName()).isEqualTo("local-hash-v1");
        assertThat(engine.getDimension()).isEqualTo(128);

        EmbeddingResult result = engine.generateEmbedding("测试视频标题与简介");
        assertThat(result.isValid()).isTrue();
        assertThat(result.dimension()).isEqualTo(128);
        assertThat(result.vector()).hasSize(128);
    }

    @Test
    @DisplayName("验证输出向量满足 L2 范数归一化 (模长为 1.0)")
    void shouldBeL2Normalized() {
        EmbeddingResult result = engine.generateEmbedding("深入浅出微服务架构与云原生实践");

        double sumSquares = 0.0;
        for (float v : result.vector()) {
            sumSquares += v * v;
        }

        assertThat(Math.sqrt(sumSquares)).isCloseTo(1.0, offset(1e-4));
    }

    @Test
    @DisplayName("验证相同文本多次生成具备 100% 确定性")
    void shouldBeDeterministicForSameInput() {
        String input = "Spring Boot 3.3 与 Java 21 新特性精讲";
        EmbeddingResult res1 = engine.generateEmbedding(input);
        EmbeddingResult res2 = engine.generateEmbedding(input);

        assertThat(res1.vector()).isEqualTo(res2.vector());
    }

    @Test
    @DisplayName("验证相似语义的文本余弦相似度显著高于不相关文本")
    void shouldHaveHigherSimilarityForRelatedTexts() {
        EmbeddingResult textA = engine.generateEmbedding("Java并发编程与虚拟线程性能测试");
        EmbeddingResult textB = engine.generateEmbedding("Java多线程与虚拟线程实践指南");
        EmbeddingResult textC = engine.generateEmbedding("烘焙蛋糕新手制作巧克力奶油甜点教程");

        double simAB = cosineSimilarity(textA, textB);
        double simAC = cosineSimilarity(textA, textC);

        assertThat(simAB).isGreaterThan(simAC);
        assertThat(simAB).isGreaterThan(0.3); // 相似标题应具有显著余弦相似度
    }

    @Test
    @DisplayName("验证空字符串或空白文本的健壮性防御")
    void shouldHandleBlankTextSafely() {
        EmbeddingResult res1 = engine.generateEmbedding("");
        EmbeddingResult res2 = engine.generateEmbedding("   ");
        EmbeddingResult res3 = engine.generateEmbedding(null);

        assertThat(res1.isValid()).isTrue();
        assertThat(res2.isValid()).isTrue();
        assertThat(res3.isValid()).isTrue();
    }

    private double cosineSimilarity(EmbeddingResult r1, EmbeddingResult r2) {
        double dotProduct = 0.0;
        for (int i = 0; i < r1.dimension(); i++) {
            dotProduct += r1.vector().get(i) * r2.vector().get(i);
        }
        return dotProduct;
    }
}
