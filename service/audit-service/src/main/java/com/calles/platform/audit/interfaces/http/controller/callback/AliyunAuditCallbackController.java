package com.calles.platform.audit.interfaces.http.controller.callback;

import com.calles.platform.audit.application.service.callback.AliyunAuditCallbackApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 阿里云内容安全 2.0 (Aliyun Green) 异步通知 Webhook 控制器。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核微服务 HTTP 协议适配器层，对外接收阿里云服务端的异步回调推送；</li>
 *   <li><b>鉴权规范</b>：免 Bearer Token 登录，网关统一白名单放行，由控制器内严格执行 {@code SHA-256} Checksum 防篡改验签；</li>
 *   <li><b>协议响应</b>：处理成功必须返回 HTTP 200 状态码及指定响应体，避免阿里云服务端重复重试触发风暴。</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/audit/callback/aliyun")
@RequiredArgsConstructor
public class AliyunAuditCallbackController {

    private final AliyunAuditCallbackApplicationService callbackService;

    /**
     * 接收并处理阿里云视频机审异步结果通知。
     *
     * @param checksum 阿里云推送的总和校验码
     * @param content 原始 JSON 格式的审核结果字符串
     * @return 符合阿里云回调契约的标准响应
     */
    @PostMapping(path = "/video")
    public ResponseEntity<Map<String, Object>> onVideoModerationCallback(
            @RequestParam("checksum") String checksum,
            @RequestParam("content") String content
    ) {
        log.info("收到阿里云视频机审 Webhook 回调请求");

        // 步骤 1：委托应用服务执行验签与状态机流转
        callbackService.handleVideoCallback(checksum, content);

        // 步骤 2：向阿里云返回 HTTP 200 确认接收成功
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "msg", "success"
        ));
    }
}
