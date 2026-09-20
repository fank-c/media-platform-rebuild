package com.calles.platform.recommend.infrastructure.engine;

import com.calles.platform.recommend.config.RecommendEmbeddingProperties;
import com.calles.platform.recommend.domain.engine.VectorEmbeddingEngine;
import com.calles.platform.recommend.domain.engine.model.EmbeddingResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 远程 OpenAI 兼容协议多模态向量特征提取引擎 (RemoteOpenAiEmbeddingEngine)。
 *
 * <p>职责与规范说明：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务远程大模型通信基础设施层；</li>
 *   <li><b>通用协议契约</b>：遵循标准 OpenAI 规范 {@code POST /v1/embeddings}，无缝兼容 OpenAI、智谱 AI、阿里 DashScope 或本地 Ollama；</li>
 *   <li><b>轻量化实现</b>：基于 Spring 6 / Spring Boot 3 内置 {@link RestClient}，零第三方 SDK 依赖与类冲突风险；</li>
 *   <li><b>异常出口</b>：网络超时或服务不可达时抛出专有异常，交由上层路由器评估执行本地平滑降级。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class RemoteOpenAiEmbeddingEngine implements VectorEmbeddingEngine {

    private final RecommendEmbeddingProperties properties;
    private final RestClient restClient;

    public RemoteOpenAiEmbeddingEngine(RecommendEmbeddingProperties properties) {
        this.properties = properties;

        // 步骤 1：构建带超时控制的 HTTP 请求工厂
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = properties.getOpenai().getTimeoutMs() > 0 ? properties.getOpenai().getTimeoutMs() : 5000;
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        // 步骤 2：初始化 Spring 3.3 轻量 RestClient
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String getEngineType() {
        return "remote";
    }

    @Override
    public String getModelName() {
        return properties.getOpenai().getModel();
    }

    @Override
    public int getDimension() {
        return properties.getOpenai().getDimension();
    }

    @Override
    public EmbeddingResult generateEmbedding(String text) {
        String apiKey = properties.getOpenai().getApiKey();
        String baseUrl = properties.getOpenai().getBaseUrl();
        String model = properties.getOpenai().getModel();

        // 步骤 1：防御性检查 API Key 配置
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI 兼容协议未配置 API Key，无法执行远程向量推理");
        }

        // 步骤 2：规整并标准化接口请求 URL (自动补充 /v1/embeddings 或 /embeddings 路径)
        String endpoint = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!endpoint.endsWith("/embeddings")) {
            endpoint = endpoint.endsWith("/v1") ? endpoint + "/embeddings" : endpoint + "/v1/embeddings";
        }

        // 步骤 3：构建规范请求载荷 (截断超长文本至合理长度如 4000 字符，避免超出模型上下文)
        String safeInput = text != null && text.length() > 4000 ? text.substring(0, 4000) : (text != null ? text : "");
        OpenAiEmbeddingRequest requestBody = new OpenAiEmbeddingRequest(model, safeInput);

        try {
            // 步骤 4：通过 RestClient 发起 HTTP POST 请求
            OpenAiEmbeddingResponse response = restClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(OpenAiEmbeddingResponse.class);

            // 步骤 5：校验并提取首项向量数据
            if (response == null || response.data() == null || response.data().isEmpty()) {
                throw new IllegalStateException("OpenAI 接口返回向量数据为空");
            }

            List<Float> vector = response.data().get(0).embedding();
            if (vector == null || vector.isEmpty()) {
                throw new IllegalStateException("OpenAI 返回浮点向量数据为空");
            }

            String actualModel = response.model() != null ? response.model() : model;
            return new EmbeddingResult(actualModel, vector.size(), vector);

        } catch (Exception e) {
            log.warn("调用外部 OpenAI Embedding 接口失败: endpoint={}, error={}", endpoint, e.getMessage());
            throw new RuntimeException("远程向量提取接口调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * OpenAI 请求体契约模型。
     */
    public record OpenAiEmbeddingRequest(
            @JsonProperty("model") String model,
            @JsonProperty("input") String input
    ) {}

    /**
     * OpenAI 响应体契约模型。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenAiEmbeddingResponse(
            @JsonProperty("data") List<EmbeddingData> data,
            @JsonProperty("model") String model
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record EmbeddingData(
                @JsonProperty("embedding") List<Float> embedding,
                @JsonProperty("index") Integer index
        ) {}
    }
}
