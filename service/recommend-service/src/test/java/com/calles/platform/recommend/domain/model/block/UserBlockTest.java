package com.calles.platform.recommend.domain.model.block;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserBlock 用户明确屏蔽实体单元测试。
 */
@DisplayName("UserBlock 明确屏蔽实体单元测试")
class UserBlockTest {

    @Test
    @DisplayName("create：正常构建屏蔽实体与唯一性匹配")
    void shouldCreateAndMatchBlock() {
        UserBlock block = UserBlock.create("u_001", BlockType.AUTHOR, "author_888", "低质营销号");

        assertThat(block.getId()).isNotBlank();
        assertThat(block.getUserId()).isEqualTo("u_001");
        assertThat(block.getBlockType()).isEqualTo(BlockType.AUTHOR);
        assertThat(block.getTargetId()).isEqualTo("author_888");
        assertThat(block.getReason()).isEqualTo("低质营销号");

        // 匹配逻辑校验
        assertThat(block.matches(BlockType.AUTHOR, "author_888")).isTrue();
        assertThat(block.matches(BlockType.AUTHOR, "author_999")).isFalse();
        assertThat(block.matches(BlockType.VIDEO, "author_888")).isFalse();
    }
}
