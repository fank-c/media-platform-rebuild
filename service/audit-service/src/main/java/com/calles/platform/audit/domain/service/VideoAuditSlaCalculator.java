package com.calles.platform.audit.domain.service;

import com.calles.platform.audit.config.aliyun.AliyunGreenProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 视频机审服务等级协议 (SLA) 动态超时预算计算器。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核微服务领域计算服务，负责根据音视频播放时长动态评估云端检测预算；</li>
 *   <li><b>核心算法</b>：采用基准准备底噪 + 线性时长系数加权，并辅以最小保底和最大熔断保护；</li>
 *   <li><b>协作对象</b>：供视频机审引擎和定时对账扫描器评估任务是否真正超时。</li>
 * </ul>
 * </p>
 */
@Component
@RequiredArgsConstructor
public class VideoAuditSlaCalculator {

    private final AliyunGreenProperties properties;

    /**
     * 根据视频播放时长 (秒) 计算动态机审超时时限 (秒)。
     *
     * <p>计算公式：
     * <pre>
     * timeout = clamp(baseTimeout + duration * durationRatio, minTimeout, maxTimeout)
     * </pre>
     * </p>
     *
     * @param duration 视频播放时长 (秒)
     * @return 动态预算超时时限 (秒)
     */
    public int calculateTimeoutSeconds(Integer duration) {
        // 步骤 1：合法性守卫，若时长未知或非法则默认按 0 处理
        int validDuration = (duration != null && duration > 0) ? duration : 0;

        // 步骤 2：读取配置参数
        int base = properties != null ? properties.getBaseTimeoutSeconds() : 30;
        double ratio = properties != null ? properties.getDurationRatio() : 0.6;
        int min = properties != null ? properties.getMinTimeoutSeconds() : 45;
        int max = properties != null ? properties.getMaxTimeoutSeconds() : 600;

        // 步骤 3：线性加权计算并夹取上下限
        int calculated = (int) Math.round(base + validDuration * ratio);
        return Math.clamp(calculated, min, max);
    }

    /**
     * 根据视频播放时长计算绝对截止时刻 (Instant)。
     *
     * @param duration 视频播放时长 (秒)
     * @return 绝对截止时刻
     */
    public Instant calculateDeadline(Integer duration) {
        return Instant.now().plusSeconds(calculateTimeoutSeconds(duration));
    }
}
