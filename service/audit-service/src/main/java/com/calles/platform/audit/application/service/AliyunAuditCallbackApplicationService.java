package com.calles.platform.audit.application.service;

import com.calles.platform.audit.config.AliyunGreenProperties;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.repository.AuditDetailRepository;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import com.calles.platform.audit.domain.service.AuditDecisionAggregator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 阿里云内容安全异步 Webhook 回调核心业务应用服务。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核应用服务层，处理来自阿里云内容安全服务的主动回调通知；</li>
 *   <li><b>安全防篡改</b>：基于 {@code SHA-256(UID + Seed + Content)} 规则执行严格验签；</li>
 *   <li><b>状态机推进</b>：解析视频判定结果、补充维度证据明细、重新汇总仲裁，并驱动任务聚合根跃迁及联动下游 {@code content-service}。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AliyunAuditCallbackApplicationService {

    private final AliyunGreenProperties properties;
    private final AuditTaskRepository auditTaskRepository;
    private final AuditDetailRepository auditDetailRepository;
    private final AuditDecisionAggregator decisionAggregator;
    private final AuditCallbackService callbackService;
    private final ObjectMapper objectMapper;

    /**
     * 校验阿里云回调签名 Checksum 是否合法。
     *
     * @param checksum 阿里云推送的签名散列
     * @param content 原始 JSON 字符串载荷
     * @return true 若签名匹配有效
     */
    public boolean verifyChecksum(String checksum, String content) {
        if (checksum == null || checksum.isBlank() || content == null) {
            return false;
        }

        String uid = properties.getUid() != null ? properties.getUid() : "";
        String seed = properties.getCallbackSeed() != null ? properties.getCallbackSeed() : "";
        String target = uid + seed + content;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(target.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString().equalsIgnoreCase(checksum.trim());
        } catch (NoSuchAlgorithmException e) {
            log.error("计算 SHA-256 签名异常: {}", e.getMessage(), e);
            throw new IllegalStateException("不支持 SHA-256 摘要算法", e);
        }
    }

    /**
     * 处理阿里云视频机审异步结果通知。
     *
     * @param checksum 防篡改签名
     * @param content 审核明细 JSON 字符串
     */
    @Transactional
    public void handleVideoCallback(String checksum, String content) {
        log.info("收到阿里云视频审核 Webhook 回调推送");

        // 步骤 1：严格校验 SHA-256 签名
        if (!verifyChecksum(checksum, content)) {
            log.warn("阿里云视频机审回调签名校验未通过: checksum=[{}]", checksum);
            throw new IllegalArgumentException("回调签名非法，拒绝消费");
        }

        try {
            // 步骤 2：反序列化 content 节点
            JsonNode root = objectMapper.readTree(content);
            String dataId = root.hasNonNull("DataId") ? root.get("DataId").asText() : (root.hasNonNull("dataId") ? root.get("dataId").asText() : null);
            String aliyunTaskId = root.hasNonNull("TaskId") ? root.get("TaskId").asText() : (root.hasNonNull("taskId") ? root.get("taskId").asText() : null);
            String riskLevel = root.hasNonNull("RiskLevel") ? root.get("RiskLevel").asText() : (root.hasNonNull("riskLevel") ? root.get("riskLevel").asText() : "none");

            if (dataId == null || dataId.isBlank()) {
                log.error("阿里云回调数据缺失业务 DataId，无法关联任务: content=[{}]", content);
                return;
            }

            // 步骤 3：加载对应的审核任务聚合根
            Optional<AuditTask> taskOpt = auditTaskRepository.findById(dataId);
            if (taskOpt.isEmpty()) {
                log.warn("未查找到对应的审核任务聚合根: taskId=[{}], 忽略该回调", dataId);
                return;
            }
            AuditTask task = taskOpt.get();

            // 步骤 4：幂等守卫：若任务已产生终局，直接忽略
            if (task.getStage() == AuditStage.FINISHED) {
                log.info("任务 [{}] 已处于终局已完结状态，幂等忽略本次回调", task.getTaskNo());
                return;
            }

            // 步骤 5：将阿里云风险等级映射至系统领域模型
            ReviewLevel level = mapRiskLevel(riskLevel);
            List<String> hitWords = new ArrayList<>();
            List<String> logs = new ArrayList<>();
            logs.add("阿里云视频回调裁决: riskLevel=" + riskLevel + ", taskId=" + aliyunTaskId);

            if (root.has("Result") && root.get("Result").isArray()) {
                for (JsonNode resNode : root.get("Result")) {
                    String label = resNode.path("Label").asText();
                    if (!label.isBlank()) {
                        hitWords.add(label);
                        logs.add("命中标签: " + label + " (" + resNode.path("Suggestion").asText() + ")");
                    }
                }
            }

            EngineAuditResult videoResult = EngineAuditResult.builder()
                    .dimension(AuditDimension.VIDEO)
                    .engineType("ALIYUN_GREEN_VIDEO")
                    .level(level)
                    .confidence(BigDecimal.valueOf(100.00))
                    .hitWords(hitWords)
                    .detailLog(String.join("；", logs))
                    .build();

            // 步骤 6：持久化新的视频维度审核明细
            auditDetailRepository.insert(videoResult.toAuditDetail(task.getId()));

            // 步骤 7：重新查询当前任务的所有维度明细并执行综合仲裁
            List<AuditDetail> currentDetails = auditDetailRepository.findByTaskId(task.getId());
            List<EngineAuditResult> allResults = currentDetails.stream()
                    .map(d -> EngineAuditResult.fromAuditDetail(d, ""))
                    .toList();

            AuditDecisionAggregator.Decision decision = decisionAggregator.aggregate(allResults);

            // 步骤 8：推进任务聚合根状态机
            task.completeAsyncMachineAudit(decision.overallLevel(), decision.summaryReason(), "ALIYUN_CALLBACK");
            auditTaskRepository.updateById(task);

            log.info("任务 [{}] 经阿里云视频回调后状态更新为: stage=[{}], result=[{}]",
                    task.getTaskNo(), task.getStage(), task.getResult());

            // 步骤 9：若达成终局放行或驳回，驱动下游 content-service 门禁流转
            if (task.getStage() == AuditStage.FINISHED) {
                callbackService.callbackContentService(task);
            }

        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            log.error("处理阿里云视频回调内容发生异常: error=[{}]", e.getMessage(), e);
            throw new RuntimeException("处理阿里云回调失败", e);
        }
    }

    private ReviewLevel mapRiskLevel(String riskLevel) {
        if ("high".equalsIgnoreCase(riskLevel)) {
            return ReviewLevel.ILLEGAL;
        } else if ("medium".equalsIgnoreCase(riskLevel)) {
            return ReviewLevel.SUSPICIOUS;
        }
        return ReviewLevel.NORMAL;
    }
}
