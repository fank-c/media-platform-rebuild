package com.calles.platform.interaction.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.exception.LockAcquireTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;

@ExtendWith(MockitoExtension.class)
class RedisLockServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    @Test
    @DisplayName("Redisson 正常加锁：使用看门狗自动续期，执行完毕后在 finally 中释放锁")
    void shouldAcquireRedissonLockWithWatchdogAndReleaseInFinally() throws Exception {
        when(redissonClient.getLock("int:lock:test")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        RedisLockService lockService = new RedisLockService(redissonClient);

        String result = lockService.executeWithLock("int:lock:test", Duration.ofSeconds(1), () -> "watchdog-success");

        assertThat(result).isEqualTo("watchdog-success");
        // 验证看门狗模式加锁（未指定租约时长）
        verify(rLock).tryLock(anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("Redisson 锁等待超时：抛出专用 LockAcquireTimeoutException 且严禁降级穿透分布式互斥")
    void shouldThrowLockAcquireTimeoutExceptionWhenLockUnavailable() throws Exception {
        when(redissonClient.getLock("int:lock:busy")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);

        RedisLockService lockService = new RedisLockService(redissonClient);

        assertThatThrownBy(() -> lockService.executeWithLock("int:lock:busy", Duration.ofMillis(100), () -> "fail"))
                .isInstanceOf(LockAcquireTimeoutException.class)
                .hasMessageContaining("int:lock:busy");

        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("Redis 宕机或网络异常报错时：真正平滑降级至本地 JVM 细粒度锁兜底执行")
    void shouldFallbackToLocalLockWhenRedisThrowsConnectionException() throws Exception {
        when(redissonClient.getLock("int:lock:redis-down")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new RedisConnectionException("Redis connection refused"));

        RedisLockService lockService = new RedisLockService(redissonClient);

        String result = lockService.executeWithLock("int:lock:redis-down", Duration.ofMillis(200), () -> "fallback-ok");

        assertThat(result).isEqualTo("fallback-ok");
    }

    @Test
    @DisplayName("RedissonClient 为 null 未配置时：直接平滑降级至本地 JVM 细粒度锁运行")
    void shouldFallbackToLocalLockWhenRedissonClientIsNull() {
        RedisLockService lockService = new RedisLockService(null);

        String result = lockService.executeWithLock("int:lock:no-redis", Duration.ofSeconds(1), () -> "local-ok");

        assertThat(result).isEqualTo("local-ok");
    }

    @Test
    @DisplayName("业务任务内部抛出业务异常时：锁在 finally 中被安全释放，且业务异常原样向上透传，绝不被吞噬")
    void shouldPropagateBusinessExceptionAndReleaseLock() throws Exception {
        when(redissonClient.getLock("int:lock:biz-err")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        RedisLockService lockService = new RedisLockService(redissonClient);

        assertThatThrownBy(() -> lockService.executeWithLock("int:lock:biz-err", Duration.ofSeconds(1), () -> {
            throw new IllegalArgumentException("业务非法参数校验失败");
        })).isInstanceOf(IllegalArgumentException.class)
           .hasMessage("业务非法参数校验失败");

        // 验证即使业务抛出异常，锁依然在 finally 中被安全释放
        verify(rLock).unlock();
    }
}
