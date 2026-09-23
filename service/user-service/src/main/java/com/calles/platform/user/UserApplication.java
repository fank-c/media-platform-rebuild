package com.calles.platform.user;

import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.calles.platform.user.config.UserMessagingProperties;
import com.calles.platform.user.config.UserOutboxProperties;
import com.calles.platform.user.config.UserProfileProperties;

/**
 * 用户服务启动入口，只扫描 user-service 自有且显式标注的持久化 Mapper。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = "com.calles.platform.user.infrastructure.persistence.mapper", annotationClass = Mapper.class)
@EnableConfigurationProperties({UserMessagingProperties.class, UserProfileProperties.class, UserOutboxProperties.class})
public class UserApplication {
    /**
     * 启动用户资料服务，服务仅扫描本领域 Mapper，不取得其他服务的数据访问能力。
     *
     * @param args JVM 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
