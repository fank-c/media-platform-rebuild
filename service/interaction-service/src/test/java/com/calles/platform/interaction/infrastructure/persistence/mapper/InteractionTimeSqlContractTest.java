package com.calles.platform.interaction.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/** 检查关键写入 SQL 显式使用应用层 UTC 时间，而非数据库会话时钟。 */
class InteractionTimeSqlContractTest {
    @Test
    void shouldBindTimeForStarAndCounterWrites() throws NoSuchMethodException {
        List<Method> writes = List.of(
                StarItemMapper.class.getMethod("reviveById", String.class, LocalDateTime.class),
                StarFolderMapper.class.getMethod("deleteFolderById", String.class, LocalDateTime.class),
                VideoCounterMapper.class.getMethod("applyViewDelta", String.class, long.class, LocalDateTime.class),
                VideoCounterMapper.class.getMethod("applyLikeDelta", String.class, long.class, LocalDateTime.class),
                VideoCounterMapper.class.getMethod("applyStarDelta", String.class, long.class, LocalDateTime.class),
                VideoCounterMapper.class.getMethod("applyShareDelta", String.class, long.class, LocalDateTime.class));
        for (Method write : writes) {
            String sql = String.join(" ", write.getAnnotation(Update.class).value());
            assertThat(sql).as(write.getName()).contains("#{now}").doesNotContain("CURRENT_TIMESTAMP");
        }
    }
}
