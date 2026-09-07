package com.calles.platform.auth.infrastructure.scheduling;

import com.calles.platform.auth.application.ProfileBackfillService;
import com.calles.platform.auth.config.ProfileBackfillProperties;
import com.calles.platform.auth.domain.account.AuthAccount;
import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 新认证库账号资料补齐调度器，默认关闭且默认 dry-run，不提供外部管理端点。
 */
@Component
public class ProfileBackfillJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileBackfillJob.class);

    /** 补齐服务。 */
    private final ProfileBackfillService service;
    /** 补齐任务业务指标。 */
    private final AuthOperationalMetrics metrics;
    /** 补齐运行参数；启动期已完成取值约束校验。 */
    private final ProfileBackfillProperties properties;

    /** 创建补齐任务。 */
    public ProfileBackfillJob(ProfileBackfillService service, AuthOperationalMetrics metrics,
            ProfileBackfillProperties properties) {
        this.service = service;
        this.metrics = metrics;
        this.properties = properties;
    }

    /** 扫描一批候选；实际写入须同时显式 enabled=true、dry-run=false。 */
    @Scheduled(fixedDelayString = "${auth.profile-backfill.poll-interval:10s}")
    public void runBatch() {
        if (!properties.isEnabled()) {
            return;
        }
        List<AuthAccount> candidates = service.findCandidates(properties.getBatchSize());
        metrics.recordBackfillCandidates(candidates.size());
        if (properties.isDryRun()) {
            LOGGER.info("资料补齐 dry-run 完成，candidateCount={}", candidates.size());
            return;
        }
        for (AuthAccount account : candidates) {
            try {
                if (service.enqueue(account)) {
                    metrics.recordBackfillEnqueued();
                } else {
                    metrics.recordBackfillSkipped();
                }
            } catch (RuntimeException exception) {
                // 单个账号失败时继续处理本批其他账号；失败账号未写进度，会在后续轮询重试。
                metrics.recordBackfillFailed();
                LOGGER.warn("资料补齐候选入队失败，reason={}", exception.getClass().getSimpleName());
            }
        }
        LOGGER.info("资料补齐批次入队完成，enqueuedCount={}", candidates.size());
    }
}
