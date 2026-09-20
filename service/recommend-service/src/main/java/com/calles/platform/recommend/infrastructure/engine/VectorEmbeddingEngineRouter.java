package com.calles.platform.recommend.infrastructure.engine;

import com.calles.platform.recommend.domain.engine.VectorEmbeddingEngine;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 视频向量化特征提取引擎策略路由器 (VectorEmbeddingEngineRouter)。
 *
 * <p>职责说明：
 * <ul>
 *   <li><b>纯粹策略分发</b>：收集 Spring 容器中所有 {@link VectorEmbeddingEngine} 实例构建路由表；</li>
 *   <li><b>解耦具体实现</b>：面向抽象接口编程，零耦合具体实现类，严格遵守开闭原则 (OCP)；</li>
 *   <li><b>O(1) 派发</b>：根据引擎类型标识 (如 "remote", "local", "mock") 精准分发。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class VectorEmbeddingEngineRouter {

    /** 引擎类型到具体引擎实例的高速映射路由表。 */
    private final Map<String, VectorEmbeddingEngine> engineMap = new ConcurrentHashMap<>();

    public VectorEmbeddingEngineRouter(List<VectorEmbeddingEngine> engines) {
        if (engines != null) {
            for (VectorEmbeddingEngine engine : engines) {
                if (engine.getEngineType() != null) {
                    String typeKey = engine.getEngineType().toLowerCase().trim();
                    engineMap.put(typeKey, engine);
                    log.info("注册向量提取引擎: typeKey=[{}], class=[{}]", typeKey, engine.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * 根据引擎类型标识精准路由匹配的向量引擎实现。
     *
     * @param engineType 引擎类型编码 (如 "remote", "local")
     * @return 匹配的向量化引擎实例
     * @throws IllegalArgumentException 若传入类型为空或未注册匹配的引擎
     */
    public VectorEmbeddingEngine route(String engineType) {
        if (engineType == null || engineType.isBlank()) {
            log.error("路由向量引擎失败：引擎类型不能为空");
            throw new IllegalArgumentException("向量引擎类型标识不能为空");
        }

        String normalizedType = engineType.toLowerCase().trim();
        VectorEmbeddingEngine engine = engineMap.get(normalizedType);

        if (engine == null) {
            log.error("未找到支持类型 [{}] 的向量化引擎，当前可用引擎: {}", engineType, engineMap.keySet());
            throw new IllegalArgumentException("不支持的向量化引擎类型: " + engineType);
        }

        return engine;
    }

    /**
     * 判断指定引擎类型是否已在容器中完成注册。
     *
     * @param engineType 引擎类型
     * @return true 若已注册，false 否则
     */
    public boolean supports(String engineType) {
        if (engineType == null || engineType.isBlank()) {
            return false;
        }
        return engineMap.containsKey(engineType.toLowerCase().trim());
    }
}
