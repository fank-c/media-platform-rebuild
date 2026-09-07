package com.calles.platform.auth.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.calles.platform.auth.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis Lua 资源装载回归测试。
 *
 * <p>该测试不连接 Redis；它确保认证服务打包后的类路径仍包含每个原子脚本，并在构造
 * {@link SessionService} 时完成 {@code DefaultRedisScript} 的资源加载。真实 Lua 行为仍由隔离 Redis
 * 集成测试覆盖。</p>
 */
class SessionServiceScriptResourceTest {

    /** 所有会话脚本均应保留在服务私有资源目录，避免被公共模块或其他服务复用。 */
    private static final List<String> SCRIPT_PATHS = List.of(
            "redis/auth-session/create-session.lua",
            "redis/auth-session/begin-refresh-rotation.lua",
            "redis/auth-session/finish-refresh-rotation.lua",
            "redis/auth-session/abort-refresh-rotation.lua",
            "redis/auth-session/delete-session.lua");

    /** 每个资源应含 Lua 注释和受控返回，并能被 SessionService 在类初始化时读取。 */
    @Test
    void sessionLuaResourcesArePackagedAndLoadable() {
        for (String scriptPath : SCRIPT_PATHS) {
            ClassPathResource resource = new ClassPathResource(scriptPath);
            assertTrue(resource.exists(), () -> "缺少 Redis Lua 资源: " + scriptPath);
            assertTrue(read(resource).contains("return "), () -> "Lua 脚本没有受控返回: " + scriptPath);
            assertTrue(read(resource).startsWith("-- "), () -> "Lua 脚本缺少说明注释: " + scriptPath);
        }

        // 构造会话服务触发私有脚本定义加载；此处不执行 Redis 命令。
        assertDoesNotThrow(() -> new SessionService(new StringRedisTemplate(), new ObjectMapper(),
                properties(), Clock.systemUTC()));
    }

    /** 读取类路径资源文本；测试资源丢失或编码错误应直接失败。 */
    private String read(ClassPathResource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError("无法读取 Redis Lua 资源: " + resource.getPath(), ex);
        }
    }

    /** 创建满足认证配置约束的最小测试值，不读取生产配置或密钥。 */
    private AuthProperties properties() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("01234567890123456789012345678901");
        properties.setAccessTokenTtlSeconds(900);
        properties.setRefreshTokenTtlSeconds(3600);
        return properties;
    }
}
