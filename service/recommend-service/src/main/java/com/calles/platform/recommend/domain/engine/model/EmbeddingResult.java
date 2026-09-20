package com.calles.platform.recommend.domain.engine.model;

import java.util.List;

/**
 * 向量模型特征提取输出结果模型。
 *
 * <p>职责说明：
 * 承载文本特征提取产出的特征向量数组、生效模型名称及向量维度元数据。
 * </p>
 *
 * @param modelName 实际生效的向量模型名称标识 (如 text-embedding-3-small 或 local-hash-v1)
 * @param dimension 向量维度大小 (如 1536 或 128)
 * @param vector 归一化后的浮点特征向量列表
 */
public record EmbeddingResult(
        String modelName,
        int dimension,
        List<Float> vector
) {
    /**
     * 校验向量结果合法性。
     *
     * @return true 若向量非空且维度与声明一致
     */
    public boolean isValid() {
        return modelName != null && !modelName.isBlank()
                && vector != null && !vector.isEmpty()
                && vector.size() == dimension;
    }
}
