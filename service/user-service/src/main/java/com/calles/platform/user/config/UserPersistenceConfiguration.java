package com.calles.platform.user.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/**
 * 用户资料持久化配置，只启用当前管理列表需要的 MySQL 分页能力。
 */
@Configuration
public class UserPersistenceConfiguration {
    /** 用户资料日期规则统一使用 UTC，避免部署主机时区改变生日上限判断。 */
    @Bean
    public Clock userClock() {
        return Clock.systemUTC();
    }

    /** 创建 MyBatis-Plus 分页拦截器，并限制单页上限防止管理查询失控。 */
    @Bean
    public MybatisPlusInterceptor userMybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
