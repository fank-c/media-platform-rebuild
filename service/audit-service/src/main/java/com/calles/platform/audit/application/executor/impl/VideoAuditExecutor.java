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
import lombok.RequiredArgsConstructor;
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

        // 步骤 1：阶段一 - 文本合规审查（标题 + 简介）
        CompletableFuture<List<EngineAuditResult>> textFuture;
        if (reusableResults.containsKey(AuditDimension.TEXT)) {
            log.info("视频 [{}] 文本维度命中历史通过结果，直接免审复用", context.bizId());
            textFuture = CompletableFuture.completedFuture(List.of(reusableResults.get(AuditDimension.TEXT)));
        } else {
            textFuture = CompletableFuture.supplyAsync(() -> {
                List<EngineAuditResult> textResults = new ArrayList<>();
                textResults.add(textAuditEngine.audit(context.title(), AuditDimension.TEXT));
                if (context.description() != null && !context.description().isBlank()) {
                    textResults.add(textAuditEngine.audit(context.description(), AuditDimension.TEXT));
                }
                return textResults;
            }, auditEngineExecutor).exceptionally(ex -> {
                log.error("文本机审引擎执行异常, 自动降级为人工复审: videoId=[{}]", context.bizId(), ex);
                return List.of(EngineAuditResult.of(
                        AuditDimension.TEXT, "LOCAL_DFA", com.calles.platform.audit.domain.model.enums.ReviewLevel.SUSPICIOUS,
                        BigDecimal.ZERO, List.of(), "文本机审异常自动降级: " + ex.getMessage()));
            });
        }

        // 步骤 2：阶段二 - 封面图片多媒体合规审查
        CompletableFuture<EngineAuditResult> imageFuture;
        if (reusableResults.containsKey(AuditDimension.IMAGE)) {
            log.info("视频 [{}] 封面维度命中历史通过结果，直接免审复用: coverFileId=[{}]", context.bizId(), context.coverFileId());
            imageFuture = CompletableFuture.completedFuture(reusableResults.get(AuditDimension.IMAGE));
        } else {
            imageFuture = CompletableFuture.supplyAsync(
                    () -> imageAuditEngine.auditCover(context.coverFileId()),
                    auditEngineExecutor
            ).exceptionally(ex -> {
                log.error("封面多媒体机审引擎执行异常, 自动降级为人工复审: coverFileId=[{}]", context.coverFileId(), ex);
                return EngineAuditResult.of(
                        AuditDimension.IMAGE, "RULE_IMAGE", com.calles.platform.audit.domain.model.enums.ReviewLevel.SUSPICIOUS,
                        BigDecimal.ZERO, List.of(), "封面机审异常自动降级: " + ex.getMessage());
            });
        }

        // 步骤 3：阶段三 - 视频流多媒体资产合规审查
        CompletableFuture<EngineAuditResult> videoFuture;
        if (reusableResults.containsKey(AuditDimension.VIDEO)) {
            log.info("视频 [{}] 视频资产命中历史通过结果，直接免审复用: videoFileId=[{}]", context.bizId(), context.videoFileId());
            videoFuture = CompletableFuture.completedFuture(reusableResults.get(AuditDimension.VIDEO));
        } else {
            videoFuture = CompletableFuture.supplyAsync(
                    () -> videoAuditEngine.auditVideo(context.videoFileId()),
                    auditEngineExecutor
            ).exceptionally(ex -> {
                log.error("主视频多媒体机审引擎执行异常, 自动降级为人工复审: videoFileId=[{}]", context.videoFileId(), ex);
                return EngineAuditResult.of(
                        AuditDimension.VIDEO, "RULE_VIDEO", com.calles.platform.audit.domain.model.enums.ReviewLevel.SUSPICIOUS,
                        BigDecimal.ZERO, List.of(), "视频机审异常自动降级: " + ex.getMessage());
            });
        }

        // 步骤 4：统一等待三阶段任务全部完成
        CompletableFuture.allOf(textFuture, imageFuture, videoFuture).join();

        // 步骤 5：汇聚多维度审查明细
        List<EngineAuditResult> engineResults = new ArrayList<>();
        engineResults.addAll(textFuture.join());
        engineResults.add(imageFuture.join());
        engineResults.add(videoFuture.join());

        // 步骤 6：基于最高安全优先级进行综合仲裁
        AuditDecisionAggregator.Decision decision = decisionAggregator.aggregate(engineResults);

        log.info("视频 [{}] 机审综合判定达成: level=[{}], reason=[{}]",
                context.bizId(), decision.overallLevel(), decision.summaryReason());

        return AuditExecutionResult.builder()
                .overallLevel(decision.overallLevel())
                .summaryReason(decision.summaryReason())
                .details(engineResults)
                .build();
    }
}
