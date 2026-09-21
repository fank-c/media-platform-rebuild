package com.calles.platform.recommend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Qdrant 向量数据库连接与集合配置属性映射。
 *
 * <p>用于管理推荐系统特征库的连接通信、集合命名与网络超时策略。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "recommend.qdrant")
public class QdrantProperties {

    /** 是否启用 Qdrant 向量数据库写入与同步。 */
    private boolean enabled = true;

    /** Qdrant 服务端网络主机地址。 */
    private String host = "localhost";

    /** Qdrant REST API 监听端口 (默认 6333)。 */
    private int port = 6333;

    /** Qdrant 服务端 API Key 认证密钥 (开启认证时必填)。 */
    private String apiKey;

    /** 视频特征集合名称。 */
    private String collectionName = "video_vectors";

    /** HTTP REST 请求连接与读取超时时间 (毫秒)。 */
    private int timeoutMs = 3000;

    /**
     * 构造完整的 Qdrant HTTP 根访问地址。
     *
     * @return 形如 http://localhost:6333 的基础 URL
     */
    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }
}
