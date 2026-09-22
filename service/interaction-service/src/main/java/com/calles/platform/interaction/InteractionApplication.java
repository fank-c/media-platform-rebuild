package com.calles.platform.interaction;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 互动服务启动引导入口。
 *
 * <p>负责点赞事实管理、多收藏夹与明细、观看历史与播放心跳机制，以及全站视频互动统计数据的聚合维护。</p>
 */
@SpringBootApplication
@MapperScan(basePackages = "com.calles.platform.interaction.infrastructure.persistence.mapper")
public class InteractionApplication {
    public static void main(String[] args) {
        SpringApplication.run(InteractionApplication.class, args);
    }
}
