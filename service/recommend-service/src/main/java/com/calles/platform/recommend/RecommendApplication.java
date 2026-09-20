package com.calles.platform.recommend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 推荐微服务 (recommend-service) 引导启动类。
 *
 * <p>职责说明：
 * <ul>
 *   <li>开启 Spring Boot 微服务容器与轻量虚拟线程池；</li>
 *   <li>开启 OpenFeign 声明式微服务远程客户端支持；</li>
 *   <li>扫描持久化仓储 Mapper 接口。</li>
 * </ul>
 * </p>
 */
@SpringBootApplication
@EnableFeignClients
@MapperScan("com.calles.platform.recommend.infrastructure.persistence.mapper")
public class RecommendApplication {
    public static void main(String[] args) {
        SpringApplication.run(RecommendApplication.class, args);
    }
}
