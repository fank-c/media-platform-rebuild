package com.calles.platform.recommend.interfaces.web;

/** 屏蔽操作缺少用户身份时抛出，区别于客户端参数错误，不改变网关鉴权规则。 */
public class MissingIdentityException extends RuntimeException {
    /** 创建缺少身份异常，提示固定且不包含用户输入或凭据。 */
    public MissingIdentityException() {
        super("当前操作需要登录身份");
    }
}
