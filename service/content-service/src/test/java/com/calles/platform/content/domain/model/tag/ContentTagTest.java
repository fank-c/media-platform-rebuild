package com.calles.platform.content.domain.model.tag;

import com.calles.platform.content.domain.model.CommonStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ContentTag 标签实体业务测试")
class ContentTagTest {

    @Test
    @DisplayName("创建新标签成功，初始热度为0且状态为ACTIVE")
    void shouldCreateTagSuccessfully() {
        ContentTag tag = ContentTag.create("tag_001", "SpringCloud");

        assertThat(tag.getId()).isEqualTo("tag_001");
        assertThat(tag.getName()).isEqualTo("SpringCloud");
        assertThat(tag.getReferenceCount()).isZero();
        assertThat(tag.getStatus()).isEqualTo(CommonStatus.ACTIVE);
        assertThat(tag.isActive()).isTrue();
    }

    @Test
    @DisplayName("空标签名或空ID创建失败抛出异常")
    void shouldFailWhenNameOrIdBlank() {
        assertThatThrownBy(() -> ContentTag.create("", "SpringCloud"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签ID不能为空");

        assertThatThrownBy(() -> ContentTag.create("tag_001", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签名称不能为空");

        assertThatThrownBy(() -> ContentTag.create("tag_001", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签名称不能为空");
    }

    @Test
    @DisplayName("标签引用热度自增与自减（保底为0）")
    void shouldIncrementAndDecrementReference() {
        ContentTag tag = ContentTag.create("tag_001", "Java");
        tag.incrementReference();
        tag.incrementReference();
        assertThat(tag.getReferenceCount()).isEqualTo(2L);

        tag.decrementReference();
        assertThat(tag.getReferenceCount()).isEqualTo(1L);

        tag.decrementReference();
        assertThat(tag.getReferenceCount()).isZero();

        // 再次自减不能为负数
        tag.decrementReference();
        assertThat(tag.getReferenceCount()).isZero();
    }

    @Test
    @DisplayName("标签屏蔽与重新启用")
    void shouldDisableAndEnable() {
        ContentTag tag = ContentTag.create("tag_001", "违规词");
        tag.disable();
        assertThat(tag.getStatus()).isEqualTo(CommonStatus.DISABLED);
        assertThat(tag.isActive()).isFalse();

        tag.enable();
        assertThat(tag.getStatus()).isEqualTo(CommonStatus.ACTIVE);
        assertThat(tag.isActive()).isTrue();
    }
}
