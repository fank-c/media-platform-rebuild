package com.calles.platform.auth.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** auth_outbox 状态持久化值的稳定性测试。 */
class AuthOutboxStatusTest {

    /**
     * 状态值必须与迁移脚本中的检查约束一致，避免重构枚举名称时破坏已有 Outbox 记录。
     */
    @Test
    void statusDatabaseValuesRemainStable() {
        Map<AuthOutboxStatus, String> expectedValues = Map.of(
                AuthOutboxStatus.PENDING, "PENDING",
                AuthOutboxStatus.PROCESSING, "PROCESSING",
                AuthOutboxStatus.PUBLISHED, "PUBLISHED",
                AuthOutboxStatus.FAILED, "FAILED");

        expectedValues.forEach((status, expectedValue) ->
                assertEquals(expectedValue, status.databaseValue()));
    }
}
