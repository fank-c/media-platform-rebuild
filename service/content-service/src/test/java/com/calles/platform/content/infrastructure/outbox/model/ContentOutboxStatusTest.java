package com.calles.platform.content.infrastructure.outbox.model;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ContentOutboxStatus 状态机持久化字面量稳定性测试。
 */
@DisplayName("ContentOutboxStatus 发件箱状态枚举测试")
class ContentOutboxStatusTest {

    /**
     * 验证状态枚举的数据库存储值严格匹配 content_outbox 表的 CHECK 约束。
     */
    @Test
    @DisplayName("状态值与数据库约束定义严格一致")
    void statusDatabaseValuesRemainStable() {
        Map<ContentOutboxStatus, String> expectedValues = Map.of(
                ContentOutboxStatus.PENDING, "PENDING",
                ContentOutboxStatus.PROCESSING, "PROCESSING",
                ContentOutboxStatus.PUBLISHED, "PUBLISHED",
                ContentOutboxStatus.FAILED, "FAILED"
        );

        expectedValues.forEach((status, expectedValue) ->
                assertEquals(expectedValue, status.databaseValue()));
    }
}
