package com.calles.platform.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 密码哈希边界。认证服务只接触登录请求中的明文瞬时值，持久化值始终是 BCrypt 哈希。
 */
@Service
public class PasswordService {

    /** BCrypt 编码器；哈希成本沿用 Spring Security 默认值，变更成本需单独评估历史登录性能。 */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 将登录密码转为可持久化的 BCrypt 哈希。调用方应先完成业务格式校验；此处只守住
     * BCrypt 的 72 UTF-8 字节上限，防止同一密码尾部被截断产生歧义。
     */
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()
                || rawPassword.toString().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("password must not be blank and must be <= 72 UTF-8 bytes");
        }
        return encoder.encode(rawPassword);
    }

    /**
     * 比对登录密码与历史哈希。损坏的哈希和格式不合法的输入统一视为不匹配，避免向客户端
     * 暴露账户存储状态。
     */
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || rawPassword.isEmpty()
                || rawPassword.toString().getBytes(StandardCharsets.UTF_8).length > 72
                || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException ex) {
            // 损坏的历史哈希按密码不匹配处理，避免把哈希格式错误暴露给调用方。
            return false;
        }
    }
}
