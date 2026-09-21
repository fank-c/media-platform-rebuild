package com.calles.platform.recommend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 推荐服务特征向量提取引擎配置属性映射。
 *
 * <p>支持参数：
 * <ul>
 *   <li>{@code type}：引擎类型 (remote / local / mock)；</li>
 *   <li>{@code fallback-to-local}：远程调用失败或未配置 Key 时是否自动回退至本地算法；</li>
 *   <li>{@code openai.*}：OpenAI 兼容协议端点、API Key、模型名称、维度与超时设置；</li>
 *   <li>{@code local.*}：本地确定性哈希算法维度配置。</li>
 * </ul>
 * </p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "recommend.embedding")
public class RecommendEmbeddingProperties {

    /** 推荐系统特征向量全局目标基准维度 (默认 1024 维)。 */
    private int dimension = 1024;

    /** 引擎模式：remote (优先远程大模型), local (纯本地算法), mock (测试桩)。 */
    private String type = "remote";

    /** 远程模型异常或未配凭据时是否自动平滑回退至本地引擎。 */
    private boolean fallbackToLocal = true;

    /** OpenAI 兼容协议配置。 */
    private OpenAiProperties openai = new OpenAiProperties();

    /** 本地哈希引擎配置。 */
    private LocalProperties local = new LocalProperties();

    @Data
    public static class OpenAiProperties {
        /** 接口基础 URL (例如 https://api.openai.com/v1 或兼容网关)。 */
        private String baseUrl = "https://api.openai.com/v1";

        /** 访问认证 API Key。 */
        private String apiKey = "";

        /** 目标 Embedding 模型名称。 */
        private String model = "text-embedding-3-small";

        /** 特征向量目标维度 (主流标准 1024)。 */
        private int dimension = 1024;

        /** HTTP 通信超时时间 (毫秒)。 */
        private int timeoutMs = 5000;
    }

    @Data
    public static class LocalProperties {
        /** 本地特征散列向量维度 (默认 1024 维)。 */
        private int dimension = 1024;
    }
}
