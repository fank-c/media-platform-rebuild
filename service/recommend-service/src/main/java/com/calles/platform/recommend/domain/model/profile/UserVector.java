package com.calles.platform.recommend.domain.model.profile;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 用户即时检索向量值对象。
 *
 * <p>表示当前用户在特征空间中的兴趣偏好向量。
 * 封装了增量指数移动平均（EMA）平滑合入算法与 L2 长度归一化，
 * 保证向量平滑演进、无断崖突变，用于在 Qdrant 中执行最近邻（ANN）召回检索。</p>
 */
@Getter
public class UserVector {

    /** 默认平滑更新系数 alpha (更新即时兴趣比例 20%，保留长期记忆 80%)。 */
    public static final double DEFAULT_ALPHA = 0.2;

    /** 平台特征向量默认基准维度 (1024 维)。 */
    public static final int DEFAULT_DIMENSION = 1024;

    /** 浮点特征向量数组 (不可变)。 */
    private final List<Float> vector;

    /** 向量维度大小。 */
    private final int dimension;

    /** 向量最后更新时间。 */
    private final LocalDateTime updatedAt;

    public UserVector(List<Float> vector, int dimension, LocalDateTime updatedAt) {
        this.dimension = dimension > 0 ? dimension : (vector != null && !vector.isEmpty() ? vector.size() : DEFAULT_DIMENSION);
        this.vector = vector != null ? Collections.unmodifiableList(new ArrayList<>(vector)) : Collections.emptyList();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    /**
     * 工厂方法：创建指定维度的冷启动空向量。
     *
     * @param dimension 预期特征维度
     * @return 空用户向量实例
     */
    public static UserVector empty(int dimension) {
        return new UserVector(Collections.emptyList(), dimension > 0 ? dimension : DEFAULT_DIMENSION, LocalDateTime.now());
    }

    /**
     * 工厂方法：创建默认 1024 维度的冷启动空向量。
     *
     * @return 空用户向量实例
     */
    public static UserVector empty() {
        return empty(DEFAULT_DIMENSION);
    }

    /**
     * 工厂方法：基于原始特征数组构建并完成 L2 归一化。
     *
     * @param rawVector 原始特征列表
     * @return 归一化后的用户向量实例
     */
    public static UserVector of(List<Float> rawVector) {
        Objects.requireNonNull(rawVector, "原始特征向量不能为空");
        List<Float> normalized = l2Normalize(rawVector);
        return new UserVector(normalized, normalized.size(), LocalDateTime.now());
    }

    /**
     * 判定当前用户向量是否尚未初始化 (冷启动阶段)。
     */
    public boolean isEmpty() {
        return vector == null || vector.isEmpty();
    }

    /**
     * 采用增量指数移动平均（EMA）平滑合入新消费视频向量。
     *
     * <p>数学公式：
     * U_new = (1 - alpha) * U_old + alpha * V_new
     * 计算后自动执行 L2 长度归一化，保证与候选视频处于同一内积/余弦尺度。</p>
     *
     * @param newVideoVector 新消费视频的特征向量
     * @param alpha 更新系数 (0.0 < alpha <= 1.0)
     * @return 平滑累加并归一化后的新 UserVector 实例
     */
    public UserVector applyEma(List<Float> newVideoVector, double alpha) {
        // 步骤 1：入参前置校验
        if (newVideoVector == null || newVideoVector.isEmpty()) {
            return this;
        }

        // 步骤 2：冷启动首向量初始化处理
        if (this.isEmpty()) {
            List<Float> normalized = l2Normalize(newVideoVector);
            return new UserVector(normalized, normalized.size(), LocalDateTime.now());
        }

        // 步骤 3：维度一致性校验
        if (this.vector.size() != newVideoVector.size()) {
            throw new IllegalArgumentException(String.format(
                    "向量维度不匹配: 用户向量维度=%d, 新视频向量维度=%d",
                    this.vector.size(), newVideoVector.size()
            ));
        }

        // 步骤 4：执行 EMA 加权计算
        double clampedAlpha = Math.max(0.01, Math.min(1.0, alpha));
        double retainWeight = 1.0 - clampedAlpha;

        int size = this.vector.size();
        List<Float> merged = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            float val = (float) (retainWeight * this.vector.get(i) + clampedAlpha * newVideoVector.get(i));
            merged.add(val);
        }

        // 步骤 5：执行 L2 归一化并返回新实例
        List<Float> normalized = l2Normalize(merged);
        return new UserVector(normalized, size, LocalDateTime.now());
    }

    /**
     * 执行 L2 模长归一化 (Unit Vector)。
     *
     * @param raw 原始浮点向量
     * @return 归一化后的浮点向量
     */
    public static List<Float> l2Normalize(List<Float> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }

        double sumSq = 0.0;
        for (Float val : raw) {
            if (val != null) {
                sumSq += val * val;
            }
        }

        double norm = Math.sqrt(sumSq);
        if (norm < 1e-9) {
            return new ArrayList<>(raw);
        }

        List<Float> result = new ArrayList<>(raw.size());
        for (Float val : raw) {
            float normalizedVal = val != null ? (float) (val / norm) : 0.0f;
            result.add(normalizedVal);
        }
        return result;
    }
}
