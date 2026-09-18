package com.calles.platform.content.domain.model.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 视频异步流水线任务类型 (TaskType) 单元测试。
 *
 * <p>测试标准编码解析以及跨服务非标别名 (如 TRANSCODE_P720) 的容错兼容。</p>
 */
class TaskTypeTest {

    @Test
    @DisplayName("null 输入直接返回 null")
    void testFromCode_null() {
        assertNull(TaskType.fromCode(null));
    }

    @ParameterizedTest
    @DisplayName("标准任务类型解析：忽略大小写与首尾空格")
    @CsvSource({
            "AUDIT, AUDIT",
            "audit, AUDIT",
            "TRANSCODE_720P, TRANSCODE_720P",
            "transcode_720p, TRANSCODE_720P",
            "TRANSCODE_1080P, TRANSCODE_1080P",
            "TRANSCODE_4K, TRANSCODE_4K",
            "VECTOR_EMBEDDING, VECTOR_EMBEDDING"
    })
    void testFromCode_standard(String input, TaskType expected) {
        assertEquals(expected, TaskType.fromCode(input));
    }

    @ParameterizedTest
    @DisplayName("非标历史/外部别名兼容解析：TRANSCODE_P* 自动映射到对应转码规格")
    @CsvSource({
            "TRANSCODE_P720, TRANSCODE_720P",
            "transcode_p720, TRANSCODE_720P",
            "TRANSCODE_P1080, TRANSCODE_1080P",
            "transcode_p1080, TRANSCODE_1080P",
            "TRANSCODE_P4K, TRANSCODE_4K",
            "transcode_p4k, TRANSCODE_4K"
    })
    void testFromCode_alias(String input, TaskType expected) {
        assertEquals(expected, TaskType.fromCode(input));
    }

    @ParameterizedTest
    @DisplayName("未知任务类型输入抛出 IllegalArgumentException")
    @ValueSource(strings = {"", " ", "UNKNOWN", "TRANSCODE_360P", "TRANSCODE_2K"})
    void testFromCode_invalid(String input) {
        assertThrows(IllegalArgumentException.class, () -> TaskType.fromCode(input));
    }
}
