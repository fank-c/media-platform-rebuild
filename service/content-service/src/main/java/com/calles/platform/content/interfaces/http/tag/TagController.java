package com.calles.platform.content.interfaces.http.tag;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.content.application.tag.ContentTagApplicationService;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签字典 HTTP API 控制器。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：提供全站通用标签词典的公共检索能力；</li>
 *   <li><b>协作对象</b>：委托 {@link ContentTagApplicationService} 完成热门标签聚合检索与数据转换；</li>
 *   <li><b>访问权限</b>：前台公开只读接口，支持未登录访客访问。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/content/tags")
@RequiredArgsConstructor
public class TagController {

    /**
     * 内容标签应用服务，负责热度计算与字典持久化。
     */
    private final ContentTagApplicationService contentTagApplicationService;

    /**
     * 获取全站热门轻量标签列表。
     *
     * @param limit 限制获取条数，默认 20 条，最大 50 条
     * @return 热门标签列表响应实体（按引用热度降序排列）
     */
    @GetMapping("/hot")
    public ApiResponse<List<VideoResponses.HotTag>> getHotTags(@RequestParam(defaultValue = "20") int limit) {
        // 步骤 1：调用应用服务拉取全站高热度标签实体列表
        List<VideoResponses.HotTag> tags = contentTagApplicationService.getHotTags(limit).stream()
                // 步骤 2：转换为对外统一轻量 DTO，隐藏领域内部更新时间等冗余属性
                .map(t -> new VideoResponses.HotTag(t.getId(), t.getName(), t.getReferenceCount()))
                .toList();
        // 步骤 3：包装为标准化成功响应
        return ApiResponse.ok(tags);
    }
}
