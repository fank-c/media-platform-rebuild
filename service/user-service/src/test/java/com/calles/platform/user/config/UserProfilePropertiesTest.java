package com.calles.platform.user.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/** UserProfileProperties 的头像前缀规范化测试。 */
class UserProfilePropertiesTest {

    /** 空配置代表不展示历史头像，仍应安全通过启动期校验。 */
    @Test
    void validateAcceptsEmptyPrefix() {
        assertDoesNotThrow(() -> new UserProfileProperties().validate());
    }

    /** 绑定的前缀应去除多余空白，避免展示策略出现意外匹配。 */
    @Test
    void setterTrimsAllowedPrefix() {
        UserProfileProperties properties = new UserProfileProperties();
        properties.setAvatarAllowedPrefix(" https://assets.example/ ");

        assertEquals("https://assets.example/", properties.getAvatarAllowedPrefix());
    }
}
