package com.calles.platform.auth;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.calles.platform.auth.config.AuthOutboxProperties;
import com.calles.platform.auth.config.AuthProperties;
import com.calles.platform.auth.config.ProfileBackfillProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 认证服务启动入口。
 *
 * <p>Mapper 扫描范围仅覆盖本服务的普通持久化和 Outbox SQL 入口，并以 {@link Mapper} 注解过滤，
 * 避免其他服务通过组件扫描取得 {@code auth_account} 的持久化能力。</p>
 */
@SpringBootApplication
@MapperScan(basePackages = {"com.calles.platform.auth.infrastructure.persistence",
        "com.calles.platform.auth.infrastructure.outbox"}, annotationClass = Mapper.class)
@EnableConfigurationProperties({AuthProperties.class, AuthOutboxProperties.class, ProfileBackfillProperties.class})
@EnableScheduling
public class AuthApplication {

    /** 启动 Spring 上下文；配置缺失会在认证组件初始化前快速失败。 */
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
