package com.calles.platform.recommend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 关注召回业务参数。
 *
 * <p>参数在应用启动时校验，避免非法窗口或候选池上限进入运行时排序。</p>
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "recommend.following")
public class FollowingRecallProperties {

    /** 关注作品时间窗口（小时）。 */
    @Min(1)
    @Max(720)
    private int windowHours = 168;

    /** 本轮评分池候选上限。 */
    @Min(1)
    @Max(100)
    private int candidateLimit = 100;

    /** 时间权重半衰期（小时）。 */
    @Min(1)
    @Max(168)
    private int halfLifeHours = 24;
}
