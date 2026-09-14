package com.calles.platform.content;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 内容服务启动入口。
 */
@SpringBootApplication
@MapperScan(basePackages = "com.calles.platform.content.infrastructure", annotationClass = Mapper.class)
public class ContentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContentApplication.class, args);
    }
}
