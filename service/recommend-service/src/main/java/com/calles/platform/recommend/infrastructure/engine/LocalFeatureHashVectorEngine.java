package com.calles.platform.recommend.infrastructure.engine;

import com.calles.platform.recommend.config.RecommendEmbeddingProperties;
import com.calles.platform.recommend.domain.engine.VectorEmbeddingEngine;
import com.calles.platform.recommend.domain.engine.model.EmbeddingResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本地确定性特征散列向量计算引擎 (LocalFeatureHashVectorEngine)。
 *
 * <p>职责与实现原理：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务本地自包含向量化能力基础设施实现；</li>
 *   <li><b>算法核心</b>：基于 Feature Hashing (Weinberger et al.) 与 N-gram 窗口特征切片，
 *       加权投影到固定维度高维特征空间；</li>
 *   <li><b>归一化度量</b>：严格执行 L2 范数归一化（模长为 1.0），使得向量内积直接等价于余弦相似度 (Cosine Similarity)；</li>
 *   <li><b>高可用确定性</b>：相同文本输入保证 100% 确定性输出，零外部网络与外部依赖，保障单测、CI 与断网环境稳定可用。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class LocalFeatureHashVectorEngine implements VectorEmbeddingEngine {

    /** 模型标识常量。 */
    public static final String MODEL_NAME = "local-hash-v1";

    /** 配置属性。 */
    private final RecommendEmbeddingProperties properties;

    public LocalFeatureHashVectorEngine(RecommendEmbeddingProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getEngineType() {
        return "local";
    }

    @Override
    public String getModelName() {
        return MODEL_NAME;
    }

    @Override
    public int getDimension() {
        int configuredDim = properties.getLocal().getDimension();
        return configuredDim > 0 ? configuredDim : 128;
    }

    @Override
    public EmbeddingResult generateEmbedding(String text) {
        int dim = getDimension();
        float[] vectorArray = new float[dim];

        // 步骤 1：入参边界防御，空文本处理为基于默认种子维度的确定性基线特征
        if (text == null || text.isBlank()) {
            vectorArray[0] = 1.0f;
            return toResult(vectorArray, dim);
        }

        // 步骤 2：中英文分词与 1-gram / 2-gram 特征元分解提取
        String normalizedText = text.trim().toLowerCase();
        List<String> tokens = extractTokens(normalizedText);

        // 步骤 3：特征散列双哈希投影 (Bucket 映射与正负符号投影消除偏差)
        for (String token : tokens) {
            byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
            int hash1 = murmurhash3_x86_32(bytes, 0, bytes.length, 0x9747b28c);
            int hash2 = murmurhash3_x86_32(bytes, 0, bytes.length, 0x1b873593);

            // 位与 0x7fffffff 确保数值非负，规避 Integer.MIN_VALUE 取绝对值仍为负数的越界陷阱
            int bucket = (hash1 & 0x7fffffff) % dim;
            float sign = (hash2 % 2 == 0) ? 1.0f : -1.0f;

            // 词长衰减加权，避免长杂质词统治权重
            float weight = (float) Math.log(1.0 + token.length());
            vectorArray[bucket] += sign * weight;
        }

        // 步骤 4：执行 L2 范数归一化，使得向量长度为 1.0 (方便后续直接以点积计算余弦相似度)
        float sumSquare = 0.0f;
        for (float val : vectorArray) {
            sumSquare += val * val;
        }

        if (sumSquare > 1e-6) {
            float norm = (float) Math.sqrt(sumSquare);
            for (int i = 0; i < dim; i++) {
                vectorArray[i] /= norm;
            }
        } else {
            // 极端空桶兜底
            vectorArray[0] = 1.0f;
        }

        return toResult(vectorArray, dim);
    }

    /**
     * 将浮点原生数组封装为强类型 EmbeddingResult 结果。
     */
    private EmbeddingResult toResult(float[] array, int dim) {
        List<Float> list = new ArrayList<>(dim);
        for (float f : array) {
            list.add(f);
        }
        return new EmbeddingResult(MODEL_NAME, dim, list);
    }

    /**
     * 抽取单字、词及滑动 2-gram 作为语义特征子序列。
     */
    private List<String> extractTokens(String text) {
        List<String> tokens = new ArrayList<>();
        // 按空格与标点切词
        String[] words = text.split("[\\s\\p{Punct}]+");
        for (String word : words) {
            if (!word.isBlank()) {
                tokens.add(word);
            }
        }

        // 针对中文或连贯字符按字符滑动提取 2-gram
        int len = text.length();
        for (int i = 0; i < len - 1; i++) {
            tokens.add(text.substring(i, i + 2));
        }
        return tokens;
    }

    /**
     * 纯 Java 高性能标准 MurmurHash3_x86_32 散列实现。
     */
    private static int murmurhash3_x86_32(byte[] data, int offset, int len, int seed) {
        int c1 = 0xcc9e2d51;
        int c2 = 0x1b873593;
        int h1 = seed;
        int roundedEnd = offset + (len & 0xfffffffc);

        for (int i = offset; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | (data[i + 3] << 24);
            k1 *= c1;
            k1 = (k1 << 15) | (k1 >>> 17);
            k1 *= c2;

            h1 ^= k1;
            h1 = (h1 << 13) | (h1 >>> 19);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        int tail = len & 0x03;
        if (tail == 3) {
            k1 ^= (data[roundedEnd + 2] & 0xff) << 16;
        }
        if (tail >= 2) {
            k1 ^= (data[roundedEnd + 1] & 0xff) << 8;
        }
        if (tail >= 1) {
            k1 ^= (data[roundedEnd] & 0xff);
            k1 *= c1;
            k1 = (k1 << 15) | (k1 >>> 17);
            k1 *= c2;
            h1 ^= k1;
        }

        h1 ^= len;
        h1 ^= (h1 >>> 16);
        h1 *= 0x85ebca6b;
        h1 ^= (h1 >>> 13);
        h1 *= 0xc2b2ae35;
        h1 ^= (h1 >>> 16);
        return h1;
    }
}
