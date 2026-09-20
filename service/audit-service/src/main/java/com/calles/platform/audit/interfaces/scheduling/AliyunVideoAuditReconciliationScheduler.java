package com.calles.platform.audit.interfaces.scheduling;

import com.calles.platform.audit.application.service.AuditCallbackService;
import com.calles.platform.audit.config.aliyun.AliyunGreenProperties;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.repository.AuditDetailRepository;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import com.calles.platform.audit.domain.service.AuditDecisionAggregator;
import com.calles.platform.audit.infrastructure.engine.aliyun.AliyunGreenVideoAuditEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 阿里云视频机审异步结果定时对账与超时补偿调度器。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核微服务接口调度层，负责针对长时间处于 {@link AuditStage#MACHINE_AUDITING} 的悬挂视频任务执行主动对账与兜底收割；</li>
 *   <li><b>双轨协同机制</b>：
 *     <ul>
 *       <li>与云端 Webhook 互为双保险：Webhook 负责毫秒级推送通知，本调度器负责对账补漏与超时降级；</li>
 *       <li>未到动态 SLA 截止时刻时，主动向阿里云查询是否已完成检测并推进终局；</li>
 *       <li>超过动态 SLA 截止时刻时，优雅降级为 {@link AuditStage#MANUAL_PENDING} 推入人工复审，绝不阻塞系统整体流水线。</li>
 *     </ul>
 *   </li>
 *   <li><b>幂等并发安全</b>：基于聚合根的终态守卫，任何已被 Webhook 完结的任务自动安全跳过。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "audit.aliyun.enabled", havingValue = "true")
public class AliyunVideoAuditReconciliationScheduler {

    private final AuditTaskRepository auditTaskRepository;
    private final AuditDetailRepository auditDetailRepository;
    private final ObjectProvider<AliyunGreenVideoAuditEngine> videoAuditEngineProvider;
    private final AuditDecisionAggregator decisionAggregator;
    private final AuditCallbackService callbackService;
    private final AliyunGreenProperties properties;

    /**
     * 定时扫描机审中视频任务，执行主动探针查询与动态超时判定。
     */
    @Scheduled(fixedDelayString = "${audit.aliyun.reconcile-interval-ms:15000}")
    public void reconcileVideoAudits() {
        AliyunGreenVideoAuditEngine videoAuditEngine = videoAuditEngineProvider.getIfAvailable();
        if (videoAuditEngine == null || !properties.isEnabled()) {
            return;
        }

        // 步骤 1：批量拉取处于 MACHINE_AUDITING 机审中状态的任务
        List<AuditTask> runningTasks = auditTaskRepository.findRunningMachineAuditTasks(50);
        if (runningTasks.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        log.debug("定时对账调度启动: 发现 [{}] 个处于机审中的候选任务", runningTasks.size());

        for (AuditTask task : runningTasks) {
            try {
                // 步骤 2：保护窗口：过滤掉提交不足 3 秒的任务，避免与刚发起的调用发生过密并发
                if (task.getCreatedAt() != null && Duration.between(task.getCreatedAt(), now).toSeconds() < 3) {
                    continue;
                }

                reconcileSingleTask(task, videoAuditEngine, now);
            } catch (Exception ex) {
                log.error("单任务定时对账处理异常: taskNo=[{}], error={}", task.getTaskNo(), ex.getMessage(), ex);
            }
        }
    }

    /**
     * 对单个处于机审中的任务执行对账处理。
     */
    private void reconcileSingleTask(AuditTask task, AliyunGreenVideoAuditEngine engine, Instant now) {
        // 步骤 1：查询任务关联的所有维度判定明细
        List<AuditDetail> details = auditDetailRepository.findByTaskId(task.getId());
        AuditDetail videoDetail = details.stream()
                .filter(d -> d.getDimension() == AuditDimension.VIDEO)
                .findFirst()
                .orElse(null);

        if (videoDetail == null) {
            return;
        }

        String detailLog = videoDetail.getDetailLog();
        if (detailLog == null || !detailLog.contains("ALIYUN_TASK_ID:")) {
            return;
        }

        // 步骤 2：从 detailLog 中提取 aliyunTaskId 与动态截止时刻 deadline
        String aliyunTaskId = extractValue(detailLog, "ALIYUN_TASK_ID");
        Instant deadline = extractDeadline(detailLog, task.getCreatedAt());

        if (aliyunTaskId == null || aliyunTaskId.isBlank()) {
            return;
        }

        // 步骤 3：判断是否真正超过动态超时时限
        boolean isExpired = now.isAfter(deadline) || now.equals(deadline);

        if (!isExpired) {
            // 情况 A：在 SLA 时限内，主动发起单次查询探针
            EngineAuditResult pollResult = engine.queryVideoModerationResult(aliyunTaskId);
            if (pollResult != null) {
                log.info("定时对账探针检测到阿里云视频审核已完成: taskNo=[{}], aliyunTaskId=[{}], level=[{}]",
                        task.getTaskNo(), aliyunTaskId, pollResult.level());
                applyTerminalResult(task, pollResult);
            }
        } else {
            // 情况 B：真正超过动态 SLA 截止时刻，安全降级转入人工复审池 (MANUAL_PENDING)
            log.warn("视频机审超过按体量预算的动态截止时间 (Deadline: [{}]), 触发优雅降级转人工复审: taskNo=[{}], aliyunTaskId=[{}]",
                    deadline, task.getTaskNo(), aliyunTaskId);

            task.completeAsyncMachineAudit(
                    ReviewLevel.SUSPICIOUS,
                    "视频机审超过动态时限 (" + aliyunTaskId + ")，自动降级转人工复审",
                    "SLA_TIMEOUT_RECONCILER"
            );
            auditTaskRepository.updateById(task);
        }
    }

    /**
     * 将查询得出的终局视频结果应用至任务并触发下游微服务回调。
     */
    private void applyTerminalResult(AuditTask task, EngineAuditResult videoResult) {
        // 步骤 1：持久化终局视频明细记录
        auditDetailRepository.insert(videoResult.toAuditDetail(task.getId()));

        // 步骤 2：重新聚合全部维度的最新证据
        List<AuditDetail> updatedDetails = auditDetailRepository.findByTaskId(task.getId());
        List<EngineAuditResult> allResults = updatedDetails.stream()
                .map(d -> EngineAuditResult.fromAuditDetail(d, ""))
                .toList();

        AuditDecisionAggregator.Decision decision = decisionAggregator.aggregate(allResults);

        // 步骤 3：推进任务状态机跃迁
        task.completeAsyncMachineAudit(decision.overallLevel(), decision.summaryReason(), "RECONCILIATION_SCHEDULER");
        auditTaskRepository.updateById(task);

        log.info("定时对账成功推进审核任务终局: taskNo=[{}], stage=[{}], result=[{}]",
                task.getTaskNo(), task.getStage(), task.getResult());

        // 步骤 4：终态自动联动 downstream content-service 门禁流转
        if (task.getStage() == AuditStage.FINISHED) {
            callbackService.callbackContentService(task);
        }
    }

    /**
     * 从键值对管道字符串中提取指定 key 的值。
     */
    private String extractValue(String text, String key) {
        String prefix = key + ":";
        int start = text.indexOf(prefix);
        if (start < 0) return null;
        start += prefix.length();
        int end = text.indexOf("|", start);
        if (end < 0) end = text.length();
        return text.substring(start, end).trim();
    }

    /**
     * 解析截止时刻，若格式异常则以创建时间 + 120s 作为安全保底。
     */
    private Instant extractDeadline(String text, Instant fallbackCreatedAt) {
        String deadlineStr = extractValue(text, "DEADLINE");
        if (deadlineStr != null && !deadlineStr.isBlank()) {
            try {
                return Instant.parse(deadlineStr);
            } catch (Exception ignored) {
            }
        }
        Instant base = (fallbackCreatedAt != null) ? fallbackCreatedAt : Instant.now();
        return base.plusSeconds(120);
    }
}
