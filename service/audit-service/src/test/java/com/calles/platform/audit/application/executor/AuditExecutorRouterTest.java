package com.calles.platform.audit.application.executor;

import com.calles.platform.audit.application.executor.model.AuditBizType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审核执行器路由派发组件 {@link AuditExecutorRouter} 单元测试。
 */
class AuditExecutorRouterTest {

    @Test
    @DisplayName("通过 AuditBizType 强类型枚举能够精准定位到对应的执行器")
    void shouldRouteByAuditBizType() {
        AuditExecutor videoExecutor = mock(AuditExecutor.class);
        when(videoExecutor.getBizType()).thenReturn(AuditBizType.VIDEO);

        AuditExecutorRouter router = new AuditExecutorRouter(List.of(videoExecutor));

        assertThat(router.route(AuditBizType.VIDEO)).isSameAs(videoExecutor);
    }

    @Test
    @DisplayName("通过字符串业务编码能够平滑兼容并正确定位执行器")
    void shouldRouteByStringCode() {
        AuditExecutor videoExecutor = mock(AuditExecutor.class);
        when(videoExecutor.getBizType()).thenReturn(AuditBizType.VIDEO);

        AuditExecutorRouter router = new AuditExecutorRouter(List.of(videoExecutor));

        assertThat(router.route("VIDEO")).isSameAs(videoExecutor);
        assertThat(router.route("video")).isSameAs(videoExecutor);
        assertThat(router.route("  VIDEO  ")).isSameAs(videoExecutor);
    }

    @Test
    @DisplayName("当请求未注册的业务类型时抛出受控 IllegalArgumentException")
    void shouldThrowWhenBizTypeNotSupported() {
        AuditExecutor videoExecutor = mock(AuditExecutor.class);
        when(videoExecutor.getBizType()).thenReturn(AuditBizType.VIDEO);

        AuditExecutorRouter router = new AuditExecutorRouter(List.of(videoExecutor));

        assertThatThrownBy(() -> router.route(AuditBizType.COMMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的审核业务类型");

        assertThatThrownBy(() -> router.route("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的审核业务类型");
    }

    @Test
    @DisplayName("当路由入参为 null 时抛出 IllegalArgumentException 拦截非法请求")
    void shouldThrowWhenBizTypeIsNull() {
        AuditExecutorRouter router = new AuditExecutorRouter(List.of());

        assertThatThrownBy(() -> router.route((AuditBizType) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("审核业务类型不能为空");

        assertThatThrownBy(() -> router.route((String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的审核业务类型");
    }
}
