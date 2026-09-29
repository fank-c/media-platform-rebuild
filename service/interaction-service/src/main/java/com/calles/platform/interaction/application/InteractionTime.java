package com.calles.platform.interaction.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** 将注入时钟的瞬时值转换为数据库 DATETIME 使用的 UTC 本地时间。 */
public final class InteractionTime {
    private InteractionTime() {
    }

    /**
     * 只读取一次时钟，供一次用例的所有业务变更共享。
     *
     * @param clock 统一的服务端时钟
     * @return UTC 语义的业务时间
     */
    public static LocalDateTime utcNow(Clock clock) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(clock, "时钟不能为空").instant(), ZoneOffset.UTC);
    }
}
