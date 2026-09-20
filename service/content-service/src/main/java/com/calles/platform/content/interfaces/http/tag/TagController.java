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
     * 获取全站热门轻量标签列表（支持按领域或主题类型筛选）。
     *
     * @param type 可选的标签类型 (DOMAIN 或 TOPIC)，不传则不限制类型
     * @param limit 限制获取条数，默认 20 条，最大 50 条
     * @return 热门标签列表响应实体（按引用热度降序排列）
     */
    @GetMapping("/hot")
    public ApiResponse<List<VideoResponses.HotTag>> getHotTags(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "20") int limit) {
        // 步骤 1：解析可选的标签类型枚举
        com.calles.platform.content.domain.model.tag.TagType tagType = type != null ? com.calles.platform.content.domain.model.tag.TagType.fromCode(type) : null;

        // 步骤 2：调用应用服务拉取高热度标签实体列表
        List<VideoResponses.HotTag> tags = contentTagApplicationService.getHotTags(tagType, limit).stream()
                // 步骤 3：转换为对外统一轻量 DTO，携带 tagType 信息
                .map(t -> new VideoResponses.HotTag(
                        t.getId(),
                        t.getName(),
                        t.getTagType() != null ? t.getTagType().getCode() : com.calles.platform.content.domain.model.tag.TagType.TOPIC.getCode(),
                        t.getReferenceCount()))
                .toList();
        // 步骤 4：包装为标准化成功响应
        return ApiResponse.ok(tags);
    }

    /**
     * 获取全站所有正常启用的泛化领域标签列表 (DOMAIN)。
     *
     * <p>供前台频道导航、推荐兴趣探索筛选以及创作者发布打标分类使用。</p>
     *
     * @return 领域标签列表（按热度倒序、名称正序排列）
     */
    @GetMapping("/domains")
    public ApiResponse<List<VideoResponses.HotTag>> getDomainTags() {
        // 步骤 1：调用应用服务拉取全部可用领域标签
        List<VideoResponses.HotTag> domains = contentTagApplicationService.getDomainTags().stream()
                .map(t -> new VideoResponses.HotTag(
                        t.getId(),
                        t.getName(),
                        com.calles.platform.content.domain.model.tag.TagType.DOMAIN.getCode(),
                        t.getReferenceCount()))
                .toList();
        // 步骤 2：包装返回
        return ApiResponse.ok(domains);
    }
}
