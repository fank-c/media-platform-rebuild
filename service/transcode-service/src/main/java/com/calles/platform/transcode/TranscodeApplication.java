package com.calles.platform.transcode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 视频多规格转码压制微服务 (transcode-service) 启动入口。
 *
 * <p>核心职责：
 * <ul>
 *   <li>开启 Nacos 注册中心服务注册与发现 (EnableDiscoveryClient)；</li>
 *   <li>扫描受信任微服务内部 OpenFeign 客户端 (EnableFeignClients)；</li>
 *   <li>集成公共 Web 基础设施组件。</li>
 * </ul>
 * </p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.calles.platform.transcode",
        "com.calles.platform.common.web"
})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.calles.platform.transcode.application.client")
public class TranscodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TranscodeApplication.class, args);
    }
}
