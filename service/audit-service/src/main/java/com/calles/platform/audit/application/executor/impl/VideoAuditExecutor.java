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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频业务专属审核执行器实现 (VideoAuditExecutor)。
 *
 * <p>负责编排视频标题、简介文本敏感词扫描、封面多媒体合规探测及视频资产合规审查，并汇总产出综合裁决。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoAuditExecutor implements AuditExecutor {

    private final TextAuditEngine textAuditEngine;
    private final ImageAuditEngine imageAuditEngine;
    private final VideoAuditEngine videoAuditEngine;
    private final AuditDecisionAggregator decisionAggregator;

    @Override
    public AuditBizType getBizType() {
        return AuditBizType.VIDEO;
    }

    @Override
    public AuditExecutionResult execute(AuditContext context) {
        log.info("视频审核执行器启动处理: videoId=[{}], vid=[{}]", context.bizId(), context.bizVid());

        List<EngineAuditResult> engineResults = new ArrayList<>();

        // 步骤 1：文本合规审查（标题）
        engineResults.add(textAuditEngine.audit(context.title(), AuditDimension.TEXT));

        // 步骤 2：文本合规审查（简介，若非空）
        if (context.description() != null && !context.description().isBlank()) {
            engineResults.add(textAuditEngine.audit(context.description(), AuditDimension.TEXT));
        }

        // 步骤 3：多媒体封面合规审查
        engineResults.add(imageAuditEngine.auditCover(context.coverFileId()));

        // 步骤 4：主视频多媒体资产合规审查
        engineResults.add(videoAuditEngine.auditVideo(context.videoFileId()));

        // 步骤 5：基于最高安全优先级进行综合仲裁
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
