package com.calles.platform.content.domain.model.tag;

import com.calles.platform.content.domain.model.CommonStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ContentTag 标签实体业务与领域行为单元测试。
 * <p>
 * 覆盖标签的工厂构建、参数自校验规则、引用热度增减保底机制以及状态启闭等领域行为。
 */
@DisplayName("ContentTag 标签实体业务测试")
class ContentTagTest {

    /**
     * 测试创建新标签的正常路径，校验默认字段初始化与可用状态。
     */
    @Test
    @DisplayName("创建新标签成功，初始热度为0且状态为ACTIVE")
    void shouldCreateTagSuccessfully() {
        // 步骤 1: 模拟通过静态工厂方法创建标签
        ContentTag tag = ContentTag.create("tag_001", "SpringCloud");

        // 步骤 2: 断言初始状态与字段
        assertThat(tag.getId()).isEqualTo("tag_001");
        assertThat(tag.getName()).isEqualTo("SpringCloud");
        assertThat(tag.getReferenceCount()).isZero();
        assertThat(tag.getStatus()).isEqualTo(CommonStatus.ACTIVE);
        assertThat(tag.isActive()).isTrue();
    }

    /**
     * 测试创建标签时的必填性参数校验失败场景。
     */
    @Test
    @DisplayName("空标签名或空ID创建失败抛出异常")
    void shouldFailWhenNameOrIdBlank() {
        // 步骤 1: 验证 ID 为空时的前置断言
        assertThatThrownBy(() -> ContentTag.create("", "SpringCloud"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签ID不能为空");

        // 步骤 2: 验证标签名为空串与空白字符时的前置断言
        assertThatThrownBy(() -> ContentTag.create("tag_001", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签名称不能为空");

        assertThatThrownBy(() -> ContentTag.create("tag_001", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签名称不能为空");
    }

    /**
     * 测试标签引用计数器的自增与自减逻辑，重点验证底线不能小于 0。
     */
    @Test
    @DisplayName("标签引用热度自增与自减（保底为0）")
    void shouldIncrementAndDecrementReference() {
        // 步骤 1: 初始化标签并累加引用计数
        ContentTag tag = ContentTag.create("tag_001", "Java");
        tag.incrementReference();
        tag.incrementReference();
        assertThat(tag.getReferenceCount()).isEqualTo(2L);

        // 步骤 2: 递减引用计数
        tag.decrementReference();
        assertThat(tag.getReferenceCount()).isEqualTo(1L);

        tag.decrementReference();
        assertThat(tag.getReferenceCount()).isZero();

        // 步骤 3: 边界测试：再次自减不能为负数，保底保持为 0
        tag.decrementReference();
        assertThat(tag.getReferenceCount()).isZero();
    }

    /**
     * 测试标签状态的人工禁用与重新启用状态流转。
     */
    @Test
    @DisplayName("标签屏蔽与重新启用")
    void shouldDisableAndEnable() {
        // 步骤 1: 禁用标签并验证状态
        ContentTag tag = ContentTag.create("tag_001", "违规词");
        tag.disable();
        assertThat(tag.getStatus()).isEqualTo(CommonStatus.DISABLED);
        assertThat(tag.isActive()).isFalse();

        // 步骤 2: 重新启用标签并验证恢复状态
        tag.enable();
        assertThat(tag.getStatus()).isEqualTo(CommonStatus.ACTIVE);
        assertThat(tag.isActive()).isTrue();
    }
}
