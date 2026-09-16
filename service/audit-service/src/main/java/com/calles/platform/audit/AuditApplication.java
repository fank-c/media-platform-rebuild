package com.calles.platform.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 审核微服务启动引导类。
 *
 * 负责内容安全合规机审流水线调度、DFA 敏感词扫描、封面多媒体规则判定以及与内容服务的回调状态对齐。
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
public class AuditApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuditApplication.class, args);
    }
}
