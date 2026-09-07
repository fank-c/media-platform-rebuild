package com.calles.platform.auth.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.calles.platform.auth.domain.account.AuthAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * 认证账户持久化入口。除按登录名和 ID 读取外，不向其他服务暴露认证表访问能力。
 */
@Mapper
public interface AuthAccountMapper extends BaseMapper<AuthAccount> {

    /**
     * 按精确登录名读取账户。唯一索引保证最多一条记录；{@code LIMIT 1} 是对历史脏数据的防御，
     * 不应被当作绕过数据修复的手段。
     */
    default AuthAccount findByLoginName(String loginName) {
        LambdaQueryWrapper<AuthAccount> query = Wrappers.lambdaQuery(AuthAccount.class)
                .eq(AuthAccount::getLoginName, loginName)
                .last("LIMIT 1");
        return selectOne(query);
    }
}
