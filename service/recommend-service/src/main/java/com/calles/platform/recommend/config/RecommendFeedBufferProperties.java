package com.calles.platform.recommend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 推荐流缓冲池 (Feed Buffer Pool) 参数配置映射。
 *
 * <p>核心职责：
 * <ul>
 *   <li>管理大批次预计算生成容量（默认 30 条）；</li>
 *   <li>控制前端单次分批弹出消费的默认条数（默认 10 条）；</li>
 *   <li>配置低水位触发异步静默补水的阈值（默认 15 条）；</li>
 *   <li>限制单个用户待看队列最大容量与 Redis 过期时间（TTL）。</li>
 * </ul>
 * </p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "recommend.feed.buffer")
public class RecommendFeedBufferProperties {

    /** 是否开启推荐流 Redis 待看缓冲池 (默认 true，关闭时直接走实时计算)。 */
    private boolean enabled = true;

    /** 单次编排预生成的大包候选物料数量 (默认 30 条，足够刷 3 屏)。 */
    private int batchGenerateSize = 30;

    /** 单次返回客户端的默认卡片数量 (默认 10 条)。 */
    private int defaultPopSize = 10;

    /** 触发后台静默异步补水的低水位阈值 (队列剩余卡片 <= 该值时触发补水，默认 15)。 */
    private int lowWatermark = 15;

    /** 单个用户缓冲池队列允许积压的最大上限 (防止无限补水膨胀，默认 60)。 */
    private int maxBufferCapacity = 60;

    /** 缓冲池在 Redis 中的有效期 (秒，默认 3600 秒即 1 小时)。 */
    private long bufferTtlSeconds = 3600L;

    /** 异步补水防重并发锁的自动超时时间 (秒，默认 30 秒)。 */
    private long refillLockTimeoutSeconds = 30L;
}
