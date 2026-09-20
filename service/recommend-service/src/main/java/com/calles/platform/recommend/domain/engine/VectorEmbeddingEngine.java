package com.calles.platform.recommend.domain.engine;

import com.calles.platform.recommend.domain.engine.model.EmbeddingResult;

/**
 * 视频多模态/语义特征向量提取引擎通用契约接口。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务领域能力层核心接口；</li>
 *   <li><b>多实现扩展</b>：支持标准远程大模型适配（OpenAI 兼容协议）与本地确定性特征散列保底算法；</li>
 *   <li><b>幂等性契约</b>：相同文本输入在同一模型实现下应产生确定性高维特征向量。</li>
 * </ul>
 * </p>
 */
public interface VectorEmbeddingEngine {

    /**
     * 获取引擎类型标识 (如 "remote", "local", "mock")。
     *
     * @return 引擎类型字符串
     */
    String getEngineType();

    /**
     * 获取当前引擎默认或配置的模型名称标识。
     *
     * @return 模型标识名称
     */
    String getModelName();

    /**
     * 获取特征向量声明维度。
     *
     * @return 向量维度数
     */
    int getDimension();

    /**
     * 对输入文本内容进行语义特征抽取并生成特征向量。
     *
     * @param text 待提取特征的输入文本 (包含标题、描述或标签等组合)
     * @return 包含向量与元数据的计算产物
     */
    EmbeddingResult generateEmbedding(String text);
}
