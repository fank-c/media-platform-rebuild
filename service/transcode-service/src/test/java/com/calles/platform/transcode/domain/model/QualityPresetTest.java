package com.calles.platform.transcode.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 视频清晰度规格枚举 (QualityPreset) 单元测试。
 *
 * <p>核心核验项：
 * <ul>
 *   <li>枚举常量名称与业务标准 code 的映射规范；</li>
 *   <li>toTaskType() 方法与 content-service 的 TaskType 契约严格对齐；</li>
 *   <li>fromCode() 兼容多种格式输入的鲁棒性。</li>
 * </ul>
 * </p>
 */
class QualityPresetTest {

    @Test
    @DisplayName("核验 QualityPreset 枚举项的业务标准编码与流水线任务类型映射")
    void testPresetCodeAndTaskType() {
        assertEquals("720P", QualityPreset.P720.getCode());
        assertEquals("TRANSCODE_720P", QualityPreset.P720.toTaskType());

        assertEquals("1080P", QualityPreset.P1080.getCode());
        assertEquals("TRANSCODE_1080P", QualityPreset.P1080.toTaskType());

        assertEquals("4K", QualityPreset.P4K.getCode());
        assertEquals("TRANSCODE_4K", QualityPreset.P4K.toTaskType());
    }

    @ParameterizedTest
    @DisplayName("fromCode 容错解析：支持各种大小写、前后缀与纯数字形式")
    @CsvSource({
            "720P, P720",
            "720p, P720",
            "P720, P720",
            "p720, P720",
            "720, P720",
            "1080P, P1080",
            "1080p, P1080",
            "P1080, P1080",
            "1080, P1080",
            "4K, P4K",
            "4k, P4K",
            "P4K, P4K",
            "2160, P4K"
    })
    void testFromCode_valid(String input, QualityPreset expected) {
        assertEquals(expected, QualityPreset.fromCode(input));
    }

    @ParameterizedTest
    @DisplayName("fromCode 边界与非法输入检验：抛出 IllegalArgumentException")
    @ValueSource(strings = {"", " ", "unknown", "8K", "2K", "360P"})
    void testFromCode_invalid(String input) {
        assertThrows(IllegalArgumentException.class, () -> QualityPreset.fromCode(input));
    }

    @Test
    @DisplayName("fromCode 传入 null 时抛出 IllegalArgumentException")
    void testFromCode_null() {
        assertThrows(IllegalArgumentException.class, () -> QualityPreset.fromCode(null));
    }
}
