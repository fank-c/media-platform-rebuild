package com.calles.platform.audit.infrastructure.engine.aliyun;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.VideoModerationRequest;
import com.aliyun.green20220302.models.VideoModerationResponse;
import com.aliyun.green20220302.models.VideoModerationResponseBody;
import com.aliyun.green20220302.models.VideoModerationResultRequest;
import com.aliyun.green20220302.models.VideoModerationResultResponse;
import com.aliyun.green20220302.models.VideoModerationResultResponseBody;
import com.calles.platform.audit.application.client.FileServiceClient;
import com.calles.platform.audit.application.client.dto.FileDownloadUrlDTO;
import com.calles.platform.audit.config.aliyun.AliyunGreenProperties;
import com.calles.platform.audit.domain.engine.VideoAuditEngine;
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
 * 阿里云内容安全 2.0 (Aliyun Green 2022-03-02) 视频流资产审查引擎基础设施实现。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核服务基础设施引擎层，通过 OpenAPI 提交并评估音视频流合规性；</li>
 *   <li><b>双轨结果接收</b>：
 *     <ul>
 *       <li><b>轨道 A（本地内网轮询）</b>：未配置 callbackUrl 时，在后台线程按间隔定时调用 {@link Client#videoModerationResult} 主动探针获取结果；</li>
 *       <li><b>轨道 B（云端异步回调）</b>：配置 callbackUrl 时，提交时挂载回调地址与签名种子，结合 3 秒短探测 + Webhook 异步解耦。</li>
 *     </ul>
 *   </li>
 *   <li><b>异常韧性</b>：任务超时或网络异常时优雅降级为疑似人工审核，绝不抛出非受检异常导致提审阻塞。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "audit.aliyun.enabled", havingValue = "true")
public class AliyunGreenVideoAuditEngine implements VideoAuditEngine {

    /** 引擎标识名称。 */
    public static final String ENGINE_NAME = "ALIYUN_GREEN_VIDEO";

    private final Client client;
    private final AliyunGreenProperties properties;
    private final FileServiceClient fileServiceClient;
    private final ObjectMapper objectMapper;
    private final com.calles.platform.audit.domain.service.VideoAuditSlaCalculator slaCalculator;

    public AliyunGreenVideoAuditEngine(
            Client client,
            AliyunGreenProperties properties,
            FileServiceClient fileServiceClient,
            ObjectMapper objectMapper
    ) {
        this(client, properties, fileServiceClient, objectMapper,
                new com.calles.platform.audit.domain.service.VideoAuditSlaCalculator(properties));
    }

    public AliyunGreenVideoAuditEngine(
            Client client,
            AliyunGreenProperties properties,
            FileServiceClient fileServiceClient,
            ObjectMapper objectMapper,
            com.calles.platform.audit.domain.service.VideoAuditSlaCalculator slaCalculator
    ) {
        this.client = client;
        this.properties = properties;
        this.fileServiceClient = fileServiceClient;
        this.objectMapper = objectMapper;
        this.slaCalculator = slaCalculator != null ? slaCalculator : new com.calles.platform.audit.domain.service.VideoAuditSlaCalculator(properties);
    }

    @Override
    public AuditDimension getDimension() {
        return AuditDimension.VIDEO;
    }

    @Override
    public String getEngineType() {
        return ENGINE_NAME;
    }

    @Override
    public EngineAuditResult audit(String videoFileId) {
        return auditVideo(videoFileId);
    }

    @Override
    public EngineAuditResult auditVideo(String videoFileId) {
        com.calles.platform.audit.application.executor.model.AuditContext ctx =
                com.calles.platform.audit.application.executor.model.AuditContextHolder.get();
        String authorId = ctx != null ? ctx.authorId() : null;
        String taskId = ctx != null ? ctx.taskId() : null;
        return auditVideo(videoFileId, authorId, taskId);
    }

    @Override
    public EngineAuditResult auditVideo(String videoFileId, String authorId, String taskId) {
        log.info("阿里云视频机审启动: videoFileId=[{}], authorId=[{}], taskId=[{}]", videoFileId, authorId, taskId);

        // 步骤 1：主视频文件资产存在性校验
        if (videoFileId == null || videoFileId.isBlank()) {
            return EngineAuditResult.builder()
                    .dimension(AuditDimension.VIDEO)
                    .engineType(ENGINE_NAME)
                    .level(ReviewLevel.ILLEGAL)
                    .confidence(BigDecimal.valueOf(100.00))
                    .hitWords(List.of("MISSING_VIDEO_FILE"))
                    .detailLog("缺少主视频文件资产，审查不通过")
                    .build();
        }

        try {
            // 步骤 2：向文件微服务申请公网预签名拉流 URL
            String owner = (authorId != null && !authorId.isBlank()) ? authorId : "SYSTEM";
            ApiResponse<FileDownloadUrlDTO> urlResponse = fileServiceClient.getDownloadUrl(videoFileId, owner, "USER");
            if (urlResponse == null || urlResponse.data() == null || urlResponse.data().url() == null) {
                log.warn("无法获取视频流预签名下载直链: videoFileId=[{}], 执行优雅降级", videoFileId);
                return EngineAuditResult.builder()
                        .dimension(AuditDimension.VIDEO)
                        .engineType(ENGINE_NAME)
                        .level(ReviewLevel.SUSPICIOUS)
                        .confidence(BigDecimal.valueOf(60.00))
                        .hitWords(List.of("VIDEO_URL_UNAVAILABLE"))
                        .detailLog("未能获取视频预签名访问直链，降级转入人工审核")
                        .build();
            }
            String videoUrl = urlResponse.data().url();

            // 步骤 3：构建视频审核任务请求载荷
            Map<String, Object> serviceParams = new HashMap<>();
            serviceParams.put("url", videoUrl);
            serviceParams.put("dataId", taskId != null ? taskId : videoFileId);

            boolean hasCallback = properties.getCallbackUrl() != null && !properties.getCallbackUrl().isBlank();
            if (hasCallback) {
                serviceParams.put("callback", properties.getCallbackUrl());
                if (properties.getCallbackSeed() != null && !properties.getCallbackSeed().isBlank()) {
                    serviceParams.put("seed", properties.getCallbackSeed());
                }
            }

            VideoModerationRequest request = new VideoModerationRequest();
            request.setService(properties.getVideoService());
            request.setServiceParameters(objectMapper.writeValueAsString(serviceParams));

            // 步骤 4：向阿里云提交视频检测任务
            VideoModerationResponse submitResponse = client.videoModeration(request);
            if (submitResponse == null || submitResponse.getBody() == null) {
                log.warn("阿里云视频机审任务提交响应为空，降级转人工复审");
                return fallbackSuspicious("ALIYUN_EMPTY_RESPONSE", "阿里云视频任务提交响应为空，降级转人工审核");
            }

            VideoModerationResponseBody submitBody = submitResponse.getBody();
            if (submitBody.getCode() == null || submitBody.getCode() != 200) {
                log.warn("阿里云视频任务提交返回错误: code=[{}]", submitBody.getCode());
                return fallbackSuspicious("ALIYUN_SUBMIT_ERROR", "阿里云视频任务提交失败: code=" + submitBody.getCode());
            }

            String aliyunTaskId = (submitBody.getData() != null) ? submitBody.getData().getTaskId() : null;
            if (aliyunTaskId == null || aliyunTaskId.isBlank()) {
                log.warn("阿里云视频机审未能生成 taskId，降级转人工复审");
                return fallbackSuspicious("ALIYUN_NO_TASK_ID", "未能取得阿里云视频任务编号，降级转人工审核");
            }

            // 步骤 5：动态推导超时时限与截止时刻，提交即返回（零线程物理阻塞）
            com.calles.platform.audit.application.executor.model.AuditContext ctx =
                    com.calles.platform.audit.application.executor.model.AuditContextHolder.get();
            Integer duration = ctx != null ? ctx.duration() : 0;
            int timeoutSeconds = slaCalculator != null ? slaCalculator.calculateTimeoutSeconds(duration) : 45;
            java.time.Instant deadline = java.time.Instant.now().plusSeconds(timeoutSeconds);

            log.info("阿里云视频机审任务提交成功: aliyunTaskId=[{}], duration=[{}s], timeout=[{}s], deadline=[{}], hasCallback=[{}]",
                    aliyunTaskId, duration, timeoutSeconds, deadline, hasCallback);

            String detailLog = String.format("ALIYUN_TASK_ID:%s|DEADLINE:%s|DURATION:%d",
                    aliyunTaskId, deadline.toString(), duration != null ? duration : 0);

            return EngineAuditResult.builder()
                    .dimension(AuditDimension.VIDEO)
                    .engineType(ENGINE_NAME)
                    .level(ReviewLevel.NORMAL)
                    .confidence(BigDecimal.valueOf(100.00))
                    .hitWords(List.of("ASYNC_IN_PROGRESS"))
                    .detailLog(detailLog)
                    .build();

        } catch (Exception e) {
            log.error("调用阿里云视频机审发生未受检异常: videoFileId=[{}], error=[{}]", videoFileId, e.getMessage(), e);
            return fallbackSuspicious("ALIYUN_CALL_EXCEPTION", "阿里云视频检测通信异常: " + e.getMessage() + "，降级转人工审核");
        }
    }

    /**
     * 单次向阿里云发起视频机审结果查询探针（供系统定时对账扫描器消费，零线程 sleep 阻塞）。
     *
     * @param aliyunTaskId 阿里云任务编号
     * @return 若云端已出结果返回解析后的领域明细，若仍在处理中或查询失败返回 null
     */
    public EngineAuditResult queryVideoModerationResult(String aliyunTaskId) {
        if (aliyunTaskId == null || aliyunTaskId.isBlank()) {
            return null;
        }
        try {
            // 步骤 1：组装查询请求载荷
            Map<String, Object> queryParams = Map.of("taskId", aliyunTaskId);
            VideoModerationResultRequest queryRequest = new VideoModerationResultRequest();
            queryRequest.setService(properties.getVideoService());
            queryRequest.setServiceParameters(objectMapper.writeValueAsString(queryParams));

            // 步骤 2：发起 OpenAPI 单次查询
            VideoModerationResultResponse queryResponse = client.videoModerationResult(queryRequest);
            if (queryResponse != null && queryResponse.getBody() != null) {
                VideoModerationResultResponseBody body = queryResponse.getBody();
                if (body.getCode() != null && body.getCode() == 200 && body.getData() != null) {
                    VideoModerationResultResponseBody.VideoModerationResultResponseBodyData data = body.getData();
                    // 步骤 3：若帧或音频检测结果已产出，返回解析后的终局明细
                    if (data.getFrameResult() != null || data.getAudioResult() != null) {
                        return parseVideoResultData(data);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("单次查询阿里云视频结果发生异常: aliyunTaskId=[{}], error={}", aliyunTaskId, ex.getMessage());
        }
        return null;
    }

    /**
     * 限时轮询查询视频审核结果（保留用于离线轻量单测）。
     *
     * @param aliyunTaskId 阿里云任务 ID
     * @param timeoutSeconds 最大超时时间 (秒)
     * @param intervalMillis 查询间隔 (毫秒)
     * @return 审核明细结果
     */
    public EngineAuditResult pollForVideoResult(String aliyunTaskId, int timeoutSeconds, int intervalMillis) {
        long startTime = System.currentTimeMillis();
        long maxDurationMs = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < maxDurationMs) {
            EngineAuditResult result = queryVideoModerationResult(aliyunTaskId);
            if (result != null) {
                return result;
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("视频轮询被中断: aliyunTaskId=[{}]", aliyunTaskId);
                break;
            }
        }

        // 步骤 4：超过等待时限优雅降级
        return fallbackSuspicious("VIDEO_POLL_TIMEOUT",
                "阿里云视频检测耗时超过主动轮询上限 (" + timeoutSeconds + "s)，降级转人工审核");
    }

    /**
     * 解析阿里云视频检测明细数据并映射为领域结果模型。
     */
    public EngineAuditResult parseVideoResultData(VideoModerationResultResponseBody.VideoModerationResultResponseBodyData data) {
        ReviewLevel highestLevel = ReviewLevel.NORMAL;
        List<String> hitTags = new ArrayList<>();
        List<String> logs = new ArrayList<>();

        // 步骤 1：评估画面抽帧检测结果 (FrameResult)
        if (data.getFrameResult() != null && data.getFrameResult().getFrameSummarys() != null) {
            for (var frameSummary : data.getFrameResult().getFrameSummarys()) {
                if (frameSummary.getLabelSum() != null && frameSummary.getLabelSum() > 0) {
                    String label = frameSummary.getLabel();
                    hitTags.add(label);
                    if (isHighRiskLabel(label)) {
                        highestLevel = ReviewLevel.ILLEGAL;
                        logs.add("画面命中高危违规: " + label + " (" + frameSummary.getLabelSum() + "处)");
                    } else {
                        if (highestLevel != ReviewLevel.ILLEGAL) {
                            highestLevel = ReviewLevel.SUSPICIOUS;
                        }
                        logs.add("画面命中疑似违规: " + label + " (" + frameSummary.getLabelSum() + "处)");
                    }
                }
            }
        }

        // 步骤 2：评估伴音音频检测结果 (AudioResult)
        if (data.getAudioResult() != null && data.getAudioResult().getAudioSummarys() != null) {
            for (var audioSummary : data.getAudioResult().getAudioSummarys()) {
                if (audioSummary.getLabelSum() != null && audioSummary.getLabelSum() > 0) {
                    String label = audioSummary.getLabel();
                    hitTags.add(label);
                    if (isHighRiskLabel(label)) {
                        highestLevel = ReviewLevel.ILLEGAL;
                        logs.add("音频命中高危违规: " + label + " (" + audioSummary.getLabelSum() + "处)");
                    } else {
                        if (highestLevel != ReviewLevel.ILLEGAL) {
                            highestLevel = ReviewLevel.SUSPICIOUS;
                        }
                        logs.add("音频命中疑似违规: " + label + " (" + audioSummary.getLabelSum() + "处)");
                    }
                }
            }
        }

        String detailLog = logs.isEmpty() ? "阿里云视频多媒体检测合规正常" : String.join("；", logs);

        return EngineAuditResult.builder()
                .dimension(AuditDimension.VIDEO)
                .engineType(ENGINE_NAME)
                .level(highestLevel)
                .confidence(BigDecimal.valueOf(100.00))
                .hitWords(hitTags)
                .detailLog(detailLog)
                .build();
    }

    /**
     * 判断是否为严重高危违规标签。
     */
    private boolean isHighRiskLabel(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase();
        return lower.contains("porn") || lower.contains("violent") || lower.contains("terror") || lower.contains("contraband");
    }

    private boolean isTimeoutResult(EngineAuditResult result) {
        return result.hitWords() != null && result.hitWords().contains("VIDEO_POLL_TIMEOUT");
    }

    private EngineAuditResult fallbackSuspicious(String tag, String log) {
        return EngineAuditResult.builder()
                .dimension(AuditDimension.VIDEO)
                .engineType(ENGINE_NAME)
                .level(ReviewLevel.SUSPICIOUS)
                .confidence(BigDecimal.valueOf(50.00))
                .hitWords(List.of(tag))
                .detailLog(log)
                .build();
    }
}
