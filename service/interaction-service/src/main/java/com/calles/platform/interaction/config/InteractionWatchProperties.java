package com.calles.platform.interaction.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 观看心跳（观看进度 / 会话 / 播放资格 / 事件凭据）运行参数配置类。
 *
 * <p>职责边界：只承载阈值、窗口与保留期等可调参数，不承载业务规则实现。
 * 所有阈值均为服务端判定口径，客户端上报的 {@code videoDuration} 不参与判定。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "interaction.watch")
public class InteractionWatchProperties {

    /**
     * 观看会话超时时间：无有效心跳超过此时长即视为结束旧会话并开启新会话。
     */
    private Duration sessionTimeout = Duration.ofMinutes(30);

    /** 同一用户对同一视频重复计入播放量的冷却窗口。 */
    private Duration repeatWindow = Duration.ofHours(6);

    /** 播放量资格的最低观看时长门槛，与 30% 时长门槛取较大值。 */
    private Duration validPlayThreshold = Duration.ofSeconds(5);

    /** 单次心跳允许计入的最大有效增量时长，超出部分直接截断。 */
    private Duration maxHeartbeatDelta = Duration.ofSeconds(15);

    /** 增量时间校验容差：客户端增量不得超过服务端两次心跳间隔加该容差。 */
    private Duration heartbeatCreditTolerance = Duration.ofSeconds(2);

    /** 播放量资格的时长比例门槛（0.30 表示 30%）。 */
    private double qualificationRatio = 0.30D;

    /** 完播判定的时长与位置比例门槛（0.90 表示 90%）。 */
    private double completionRatio = 0.90D;

    /** 观看会话与事件凭据的保留期，超期且无活跃引用的数据由清理任务删除。 */
    private Duration retention = Duration.ofDays(30);

    /** 单次清理任务最多处理的行数。 */
    private int cleanupBatchSize = 500;
}
