package com.calles.platform.interaction;

import com.calles.platform.interaction.config.InteractionOutboxProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 互动服务启动引导入口。
 *
 * <p>职责边界：负责点赞事实管理、多收藏夹与明细、观看历史与播放心跳机制，以及全站视频互动统计数据的聚合维护。</p>
 * <p>注解说明：通过 {@link EnableScheduling} 开启后台调度，支持高频互动计数的异步批量刷盘任务与 Outbox 扫描。</p>
 */
@EnableScheduling
@EnableConfigurationProperties(InteractionOutboxProperties.class)
@SpringBootApplication
@MapperScan(basePackages = {
        "com.calles.platform.interaction.infrastructure.persistence.mapper",
        "com.calles.platform.interaction.infrastructure.outbox.persistence"
})
public class InteractionApplication {

    /**
     * 互动服务启动主入口。
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(InteractionApplication.class, args);
    }
}
