package com.calles.platform.auth.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证 BCrypt 哈希边界不会接受错误密码或格式损坏的历史哈希。 */
class PasswordServiceTest {

    /** 被测服务不依赖外部状态，可在所有用例中复用。 */
    private final PasswordService passwordService = new PasswordService();

    /** 编码结果不能等于明文，且只允许原始密码匹配。 */
    @Test
    void matchesBcryptHashAndRejectsWrongPassword() {
        String encoded = passwordService.encode("correct-password");

        assertNotEquals("correct-password", encoded);
        assertTrue(passwordService.matches("correct-password", encoded));
        assertFalse(passwordService.matches("wrong-password", encoded));
        assertFalse(passwordService.matches("correct-password", "not-a-bcrypt-hash"));
    }
}
