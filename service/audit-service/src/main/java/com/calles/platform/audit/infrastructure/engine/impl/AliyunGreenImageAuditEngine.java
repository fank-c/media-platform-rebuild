package com.calles.platform.audit.infrastructure.engine.impl;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.ImageModerationRequest;
import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.ImageModerationResponseBody;
import com.calles.platform.audit.application.client.FileServiceClient;
import com.calles.platform.audit.application.client.dto.FileDownloadUrlDTO;
import com.calles.platform.audit.config.AliyunGreenProperties;
import com.calles.platform.audit.domain.engine.ImageAuditEngine;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.common.core.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云内容安全 2.0 (Aliyun Green 2022-03-02) 图像/封面多媒体审查引擎基础设施实现。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核服务基础设施引擎层，通过 OpenAPI 同步调用阿里云图像检测基线模型；</li>
 *   <li><b>协作对象</b>：通过 {@link FileServiceClient} 获取公网 MinIO 预签名 URL，交由 {@link Client} 执行检测；</li>
 *   <li><b>异常韧性</b>：当远端接口超时、返回非 200 或鉴权失败时，执行优雅降级为疑似人工审核，绝不抛出非受检异常阻断提审流程。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "audit.aliyun.enabled", havingValue = "true")
public class AliyunGreenImageAuditEngine implements ImageAuditEngine {

    /** 引擎标识名称。 */
    public static final String ENGINE_NAME = "ALIYUN_GREEN_IMAGE";

    private final Client client;
    private final AliyunGreenProperties properties;
    private final FileServiceClient fileServiceClient;
    private final ObjectMapper objectMapper;

    @Override
    public AuditDimension getDimension() {
        return AuditDimension.IMAGE;
    }

    @Override
    public String getEngineType() {
        return ENGINE_NAME;
    }

    @Override
    public EngineAuditResult audit(String imageFileId) {
        return auditCover(imageFileId);
    }

    @Override
    public EngineAuditResult auditCover(String coverFileId) {
        com.calles.platform.audit.application.executor.model.AuditContext ctx =
                com.calles.platform.audit.application.executor.model.AuditContextHolder.get();
        String authorId = ctx != null ? ctx.authorId() : null;
        String taskId = ctx != null ? ctx.taskId() : null;
        return auditCover(coverFileId, authorId, taskId);
    }

    @Override
    public EngineAuditResult auditCover(String coverFileId, String authorId, String taskId) {
        log.info("阿里云图片机审启动: coverFileId=[{}], authorId=[{}], taskId=[{}]", coverFileId, authorId, taskId);

        // 步骤 1：封面资产文件缺失校验防御
        if (coverFileId == null || coverFileId.isBlank()) {
            return EngineAuditResult.builder()
                    .dimension(AuditDimension.IMAGE)
                    .engineType(ENGINE_NAME)
                    .level(ReviewLevel.ILLEGAL)
                    .confidence(BigDecimal.valueOf(100.00))
                    .hitWords(List.of("MISSING_COVER"))
                    .detailLog("缺少封面图资产，审查不通过")
                    .build();
        }

        try {
            // 步骤 2：向文件服务申请该封面资产的短期公网预签名直链
            String owner = (authorId != null && !authorId.isBlank()) ? authorId : "SYSTEM";
            ApiResponse<FileDownloadUrlDTO> urlResponse = fileServiceClient.getDownloadUrl(coverFileId, owner, "USER");
            if (urlResponse == null || urlResponse.data() == null || urlResponse.data().url() == null) {
                log.warn("无法获取封面图预签名下载直链: coverFileId=[{}], 执行优雅降级", coverFileId);
                return EngineAuditResult.builder()
                        .dimension(AuditDimension.IMAGE)
                        .engineType(ENGINE_NAME)
                        .level(ReviewLevel.SUSPICIOUS)
                        .confidence(BigDecimal.valueOf(60.00))
                        .hitWords(List.of("COVER_URL_UNAVAILABLE"))
                        .detailLog("未能获取封面预签名访问直链，降级转入人工审核")
                        .build();
            }
            String imageUrl = urlResponse.data().url();

            // 步骤 3：组装阿里云内容安全 ServiceParameters 载荷
            Map<String, Object> serviceParams = new HashMap<>();
            serviceParams.put("imageUrl", imageUrl);
            serviceParams.put("dataId", taskId != null ? taskId : coverFileId);

            ImageModerationRequest request = new ImageModerationRequest();
            request.setService(properties.getImageService());
            request.setServiceParameters(objectMapper.writeValueAsString(serviceParams));

            // 步骤 4：调用阿里云 OpenAPI 同步检测接口
            ImageModerationResponse response = client.imageModeration(request);
            if (response == null || response.getBody() == null) {
                log.warn("阿里云图片机审响应体为空，降级转人工复审");
                return EngineAuditResult.builder()
                        .dimension(AuditDimension.IMAGE)
                        .engineType(ENGINE_NAME)
                        .level(ReviewLevel.SUSPICIOUS)
                        .confidence(BigDecimal.valueOf(50.00))
                        .hitWords(List.of("ALIYUN_EMPTY_RESPONSE"))
                        .detailLog("阿里云检测响应为空，降级转人工审核")
                        .build();
            }

            ImageModerationResponseBody body = response.getBody();
            if (body.getCode() == null || body.getCode() != 200) {
                log.warn("阿里云图片机审调用返回非200: code=[{}], msg=[{}]", body.getCode(), body.getMsg());
                return EngineAuditResult.builder()
                        .dimension(AuditDimension.IMAGE)
                        .engineType(ENGINE_NAME)
                        .level(ReviewLevel.SUSPICIOUS)
                        .confidence(BigDecimal.valueOf(50.00))
                        .hitWords(List.of("ALIYUN_SERVICE_ERROR"))
                        .detailLog("阿里云检测服务异常 (" + body.getMsg() + ")，降级转人工审核")
                        .build();
            }

            // 步骤 5：解析阿里云图片多标签检测结果
            return parseImageModerationData(body.getData());

        } catch (Exception e) {
            log.error("调用阿里云图片机审发生异常: coverFileId=[{}], error=[{}]", coverFileId, e.getMessage(), e);
            // 步骤 6：异常容灾降级
            return EngineAuditResult.builder()
                    .dimension(AuditDimension.IMAGE)
                    .engineType(ENGINE_NAME)
                    .level(ReviewLevel.SUSPICIOUS)
                    .confidence(BigDecimal.valueOf(50.00))
                    .hitWords(List.of("ALIYUN_CALL_EXCEPTION"))
                    .detailLog("阿里云图片检测通信异常: " + e.getMessage() + "，降级转人工审核")
                    .build();
        }
    }

    /**
     * 解析阿里云图片检测明细数据并映射为领域结果模型。
     *
     * @param data 阿里云响应 data 节点
     * @return 映射后的引擎审核结果
     */
    private EngineAuditResult parseImageModerationData(ImageModerationResponseBody.ImageModerationResponseBodyData data) {
        if (data == null) {
            return EngineAuditResult.normal(AuditDimension.IMAGE, ENGINE_NAME, "阿里云图片检测合规正常");
        }

        String riskLevel = data.getRiskLevel();
        ReviewLevel level = ReviewLevel.NORMAL;
        if ("high".equalsIgnoreCase(riskLevel)) {
            level = ReviewLevel.ILLEGAL;
        } else if ("medium".equalsIgnoreCase(riskLevel)) {
            level = ReviewLevel.SUSPICIOUS;
        }

        BigDecimal highestConfidence = BigDecimal.valueOf(100.00);
        List<String> hitTags = new ArrayList<>();
        List<String> reasonLogs = new ArrayList<>();

        // 步骤 1：遍历多标签检测明细（如涉黄、暴恐、不良场景等）
        if (data.getResult() != null) {
            for (ImageModerationResponseBody.ImageModerationResponseBodyDataResult res : data.getResult()) {
                String label = res.getLabel();
                Float confidence = res.getConfidence();
                BigDecimal conf = confidence != null ? BigDecimal.valueOf(confidence) : BigDecimal.valueOf(80.00);
                if (label != null && !label.isBlank()) {
                    hitTags.add(label);
                    reasonLogs.add("命中标签: " + label + " (置信度: " + conf + "%)");
                }
            }
        }

        // 步骤 2：生成终审日志
        if (reasonLogs.isEmpty()) {
            reasonLogs.add(level == ReviewLevel.NORMAL ? "阿里云图片检测合规正常" : ("阿里云判定风险等级: " + riskLevel));
        }

        return EngineAuditResult.builder()
                .dimension(AuditDimension.IMAGE)
                .engineType(ENGINE_NAME)
                .level(level)
                .confidence(highestConfidence)
                .hitWords(hitTags)
                .detailLog(String.join("；", reasonLogs))
                .build();
    }
}
