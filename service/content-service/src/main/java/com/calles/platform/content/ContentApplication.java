package com.calles.platform.content;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 内容服务 (content-service) Spring Boot 启动引导入口。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：内容领域微服务入口，负责视频元数据、图文信息、标签字典、转码流资产登记以及生命周期流转。</li>
 *   <li><b>协作对象</b>：
 *     <ul>
 *       <li>{@link EnableFeignClients}：开启针对 {@code file-service} 的 OpenFeign 客户端扫描；</li>
 *       <li>{@link EnableScheduling}：开启后台任务超时巡检定时任务；</li>
 *       <li>{@link MapperScan}：扫描内容持久化 Mapper 与事务性发件箱 (Outbox) Mapper；</li>
 *       <li>注册中心与配置中心：通过 Nacos 动态拉取配置并注册服务实例。</li>
 *     </ul>
 *   </li>
 *   <li><b>防腐与不应承担的工作</b>：不直接访问其他微服务的数据库表；不承载网关协议路由与鉴权解析；不承载文件二进制流上传与转码具体计算。</li>
 * </ul>
 * </p>
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients(basePackages = "com.calles.platform.content.application.client")
@MapperScan(basePackages = "com.calles.platform.content.infrastructure", annotationClass = Mapper.class)

public class ContentApplication {

    /**
     * 内容微服务主程序入口。
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ContentApplication.class, args);
    }
}
