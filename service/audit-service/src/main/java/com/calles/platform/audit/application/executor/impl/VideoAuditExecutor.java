package com.calles.platform.audit.application.executor.impl;

import com.calles.platform.audit.application.executor.AuditExecutor;
import com.calles.platform.audit.application.executor.model.AuditBizType;
import com.calles.platform.audit.application.executor.model.AuditContext;
import com.calles.platform.audit.application.executor.model.AuditExecutionResult;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.engine.ImageAuditEngine;
import com.calles.platform.audit.domain.engine.TextAuditEngine;
import com.calles.platform.audit.domain.engine.VideoAuditEngine;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.service.AuditDecisionAggregator;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 视频业务专属审核执行器实现 (VideoAuditExecutor)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：视频机审流水线领域编排器；</li>
 *   <li><b>并发模型</b>：将文本、封面与视频三阶段审查提交至隔离线程池并发执行，并通过统一栅栏聚合结果，大幅缩短端到端时延；</li>
 *   <li><b>增量免审</b>：自动检测并复用前序终局任务中未变更且合规的资产判定结果，避免重复耗费计算资源；</li>
 *   <li><b>高可用降级</b>：任意子引擎超时或崩溃时自动降级为 SUSPICIOUS（人工复审），防止单点故障阻塞整体提审主干。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class VideoAuditExecutor implements AuditExecutor {

    private final TextAuditEngine textAuditEngine;
    private final ImageAuditEngine imageAuditEngine;
    private final VideoAuditEngine videoAuditEngine;
    private final AuditDecisionAggregator decisionAggregator;
    private final Executor auditEngineExecutor;

    @Autowired
    public VideoAuditExecutor(
            TextAuditEngine textAuditEngine,
            ImageAuditEngine imageAuditEngine,
            VideoAuditEngine videoAuditEngine,
            AuditDecisionAggregator decisionAggregator,
            @Qualifier("auditEngineExecutor") Executor auditEngineExecutor
    ) {
        this.textAuditEngine = textAuditEngine;
        this.imageAuditEngine = imageAuditEngine;
        this.videoAuditEngine = videoAuditEngine;
        this.decisionAggregator = decisionAggregator;
        this.auditEngineExecutor = (auditEngineExecutor != null) ? auditEngineExecutor : Runnable::run;
    }

    /**
     * 兼容轻量测试与无线程池注入场景的重载构造方法。
     */
    public VideoAuditExecutor(
            TextAuditEngine textAuditEngine,
            ImageAuditEngine imageAuditEngine,
            VideoAuditEngine videoAuditEngine,
            AuditDecisionAggregator decisionAggregator
    ) {
        this(textAuditEngine, imageAuditEngine, videoAuditEngine, decisionAggregator, Runnable::run);
    }

    @Override
    public AuditBizType getBizType() {
        return AuditBizType.VIDEO;
    }

    @Override
    public AuditExecutionResult execute(AuditContext context) {
        log.info("视频审核执行器启动并发机审: videoId=[{}], vid=[{}]", context.bizId(), context.bizVid());

        Map<AuditDimension, EngineAuditResult> reusableResults = context.reusableResults();

        // 步骤 1：三阶段异步并发调度（内部自动处理历史免审跳过与异常优雅降级）
        CompletableFuture<List<EngineAuditResult>> textFuture = executeOrReuse(
                AuditDimension.TEXT, reusableResults, () -> auditTexts(context), "LOCAL_DFA", "文本", context.bizId());

        CompletableFuture<List<EngineAuditResult>> imageFuture = executeOrReuse(
                AuditDimension.IMAGE, reusableResults, () -> List.of(imageAuditEngine.auditCover(context.coverFileId())), "RULE_IMAGE", "封面", context.bizId());

        CompletableFuture<List<EngineAuditResult>> videoFuture = executeOrReuse(
                AuditDimension.VIDEO, reusableResults, () -> List.of(videoAuditEngine.auditVideo(context.videoFileId())), "RULE_VIDEO", "视频", context.bizId());

        // 步骤 2：统一等待三阶段任务全部完成
        CompletableFuture.allOf(textFuture, imageFuture, videoFuture).join();

        // 步骤 3：汇聚多维度审查明细
        List<EngineAuditResult> engineResults = Stream.of(textFuture, imageFuture, videoFuture)
                .flatMap(f -> f.join().stream())
                .toList();

        // 步骤 4：基于最高安全优先级进行综合仲裁
        AuditDecisionAggregator.Decision decision = decisionAggregator.aggregate(engineResults);

        log.info("视频 [{}] 机审综合判定达成: level=[{}], reason=[{}]",
                context.bizId(), decision.overallLevel(), decision.summaryReason());

        return AuditExecutionResult.builder()
                .overallLevel(decision.overallLevel())
                .summaryReason(decision.summaryReason())
                .details(engineResults)
                .build();
    }

    /**
     * 执行特定维度的审查任务：命中历史通过结论时直接免审复用，否则提交线程池并发执行并在异常时优雅降级。
     *
     * @param dimension 审查维度
     * @param reusableResults 可复用的历史判定映射
     * @param supplier 审查任务执行供给器
     * @param engineType 降级兜底的引擎标识
     * @param fallbackPrefix 降级原因前缀描述
     * @param bizId 业务标的 ID
     * @return 异步审查结果 Future
     */
    private CompletableFuture<List<EngineAuditResult>> executeOrReuse(
            AuditDimension dimension,
            Map<AuditDimension, EngineAuditResult> reusableResults,
            Supplier<List<EngineAuditResult>> supplier,
            String engineType,
            String fallbackPrefix,
            String bizId
    ) {
        // 步骤 1：若命中历史免审结论，跳过计算直接返回
        if (reusableResults.containsKey(dimension)) {
            log.info("视频 [{}] {}维度命中历史通过结果，直接免审复用", bizId, fallbackPrefix);
            return CompletableFuture.completedFuture(List.of(reusableResults.get(dimension)));
        }

        // 步骤 2：提交隔离线程池并发计算，并挂载高可用异常降级
        return CompletableFuture.supplyAsync(supplier, auditEngineExecutor)
                .exceptionally(ex -> {
                    log.error("视频 [{}] {}机审引擎执行异常, 自动降级为人工复审", bizId, fallbackPrefix, ex);
                    return List.of(EngineAuditResult.of(
                            dimension,
                            engineType,
                            ReviewLevel.SUSPICIOUS,
                            BigDecimal.ZERO,
                            List.of(),
                            fallbackPrefix + "机审异常自动降级: " + ex.getMessage()
                    ));
                });
    }

    /**
     * 针对视频标题与简介执行敏感词审查。
     *
     * @param context 审核执行上下文
     * @return 文本维度审查明细列表
     */
    private List<EngineAuditResult> auditTexts(AuditContext context) {
        List<EngineAuditResult> textResults = new ArrayList<>();
        textResults.add(textAuditEngine.audit(context.title(), AuditDimension.TEXT));
        if (context.description() != null && !context.description().isBlank()) {
            textResults.add(textAuditEngine.audit(context.description(), AuditDimension.TEXT));
        }
        return textResults;
    }
}
