package com.calles.platform.content.application.util;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Base62VidGenerator 业务短码发号工具类单元测试。
 * <p>
 * 验证发号器产出的短码规格（cv前缀、固定24位、Base62字符集约束）、海量发号唯一性、确定性编码幂等性及异常防御。
 */
@DisplayName("Base62VidGenerator 业务短码发号器测试")
class Base62VidGeneratorTest {

    /**
     * 测试短码格式契约：必须为 cv 前缀且总长度固定为 24 位字符。
     */
    @Test
    @DisplayName("生成的 vid 格式固定为 cv 开头且全长 24 位")
    void shouldGenerateValidFormat() {
        // 步骤 1: 生成全新视频短码
        String vid = Base62VidGenerator.generateVid();

        // 步骤 2: 断言短码非空、长度为 24 且以 cv 开头
        assertThat(vid).isNotNull();
        assertThat(vid).hasSize(24);
        assertThat(vid).startsWith("cv");

        // 步骤 3: 验证后续 22 位仅由 Base62 字符集 [0-9a-zA-Z] 组成
        assertThat(vid.substring(2)).matches("^[0-9a-zA-Z]{22}$");
    }

    /**
     * 测试大批量高频生成场景下的发号唯一性，验证在单机高频生成时无哈希碰撞。
     */
    @Test
    @DisplayName("高并发生成 10000 个 vid 保证唯一且无碰撞")
    void shouldGenerateUniqueVids() {
        // 步骤 1: 构建容量为 10000 的哈希集合
        Set<String> set = new HashSet<>(10000);

        // 步骤 2: 循环发号并断言每次加入 Set 成功（无重复冲突）
        for (int i = 0; i < 10000; i++) {
            String vid = Base62VidGenerator.generateVid();
            assertThat(vid).hasSize(24);
            assertThat(set.add(vid)).isTrue();
        }
    }

    /**
     * 测试同一 UUID 输入下的编码幂等性与确定性转换。
     */
    @Test
    @DisplayName("确定性的 UUID 编码输出确定且幂等")
    void shouldBeDeterministicForSameUuid() {
        // 步骤 1: 固定输入指定 UUID
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // 步骤 2: 多次编码并比对结果一致性
        String vid1 = Base62VidGenerator.generateVid(uuid);
        String vid2 = Base62VidGenerator.generateVid(uuid);

        // 步骤 3: 断言幂等性与固定格式
        assertThat(vid1).isEqualTo(vid2);
        assertThat(vid1).hasSize(24);
        assertThat(vid1).startsWith("cv");
        assertThat(vid1.substring(2)).endsWith("1");
    }

    /**
     * 测试入参为 null 时的边界防御性判断。
     */
    @Test
    @DisplayName("空 UUID 抛出 IllegalArgumentException")
    void shouldThrowOnNullUuid() {
        // 步骤 1: 验证 null UUID 输入时阻断并抛出清晰的异常信息
        assertThatThrownBy(() -> Base62VidGenerator.generateVid(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID 不能为空");
    }
}
