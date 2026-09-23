package com.calles.platform.user.infrastructure.outbox.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("UserOutboxStatus 状态机枚举测试")
class UserOutboxStatusTest {

    @Test
    @DisplayName("状态枚举字面量与数据库值一致")
    void enumDatabaseValuesShouldMatch() {
        assertEquals("PENDING", UserOutboxStatus.PENDING.databaseValue());
        assertEquals("PROCESSING", UserOutboxStatus.PROCESSING.databaseValue());
        assertEquals("PUBLISHED", UserOutboxStatus.PUBLISHED.databaseValue());
        assertEquals("FAILED", UserOutboxStatus.FAILED.databaseValue());
    }
}
