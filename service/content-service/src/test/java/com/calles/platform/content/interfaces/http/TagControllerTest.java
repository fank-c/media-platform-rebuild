package com.calles.platform.content.interfaces.http;

import com.calles.platform.content.application.tag.ContentTagApplicationService;
import com.calles.platform.content.domain.model.tag.ContentTag;
import java.util.List;

import com.calles.platform.content.interfaces.http.tag.TagController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TagController 标签 HTTP API 控制器契约与 Web 端点单元测试。
 * <p>
 * 验证热门标签查询端点在 standalone MockMvc 环境下的 HTTP 状态码、JSON 结构及字段正确性。
 */
@DisplayName("TagController HTTP API 测试")
class TagControllerTest {

    /**
     * Spring MVC 模拟请求执行器。
     */
    private MockMvc mockMvc;

    /**
     * 模拟内容标签应用服务。
     */
    private ContentTagApplicationService tagService;

    /**
     * 初始化 MockMvc 与依赖注入。
     */
    @BeforeEach
    void setUp() {
        tagService = Mockito.mock(ContentTagApplicationService.class);
        TagController controller = new TagController(tagService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 测试 GET /api/content/tags/hot 端点契约与响应 JSON 结构。
     *
     * @throws Exception MockMvc 执行异常
     */
    @Test
    @DisplayName("GET /api/content/tags/hot 获取热门标签成功，返回 tagType 字段")
    void shouldGetHotTagsSuccessfully() throws Exception {
        // 步骤 1: 准备热门标签实体 Mock 数据
        ContentTag tag1 = ContentTag.create("t1", "Java");
        tag1.incrementReference();
        ContentTag tag2 = ContentTag.create("t2", "SpringCloud");

        when(tagService.getHotTags(null, 10)).thenReturn(List.of(tag1, tag2));

        // 步骤 2: 发送 HTTP GET 请求并断言状态码与响应体
        mockMvc.perform(get("/api/content/tags/hot?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("Java"))
                .andExpect(jsonPath("$.data[0].tagType").value("TOPIC"))
                .andExpect(jsonPath("$.data[0].referenceCount").value(1))
                .andExpect(jsonPath("$.data[1].name").value("SpringCloud"))
                .andExpect(jsonPath("$.data[1].tagType").value("TOPIC"));
    }

    /**
     * 测试 GET /api/content/tags/hot 带 type=DOMAIN 参数。
     */
    @Test
    @DisplayName("GET /api/content/tags/hot?type=DOMAIN 按领域过滤热门标签")
    void shouldGetHotTagsFilteredByType() throws Exception {
        ContentTag tagDom = ContentTag.create("t_dom", "编程", com.calles.platform.content.domain.model.tag.TagType.DOMAIN);

        when(tagService.getHotTags(com.calles.platform.content.domain.model.tag.TagType.DOMAIN, 5))
                .thenReturn(List.of(tagDom));

        mockMvc.perform(get("/api/content/tags/hot?type=DOMAIN&limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("编程"))
                .andExpect(jsonPath("$.data[0].tagType").value("DOMAIN"));
    }

    /**
     * 测试 GET /api/content/tags/domains 获取全量有效领域标签。
     */
    @Test
    @DisplayName("GET /api/content/tags/domains 获取全量领域标签")
    void shouldGetDomainTagsSuccessfully() throws Exception {
        ContentTag tagDom1 = ContentTag.create("t_dom_1", "编程", com.calles.platform.content.domain.model.tag.TagType.DOMAIN);
        ContentTag tagDom2 = ContentTag.create("t_dom_2", "摄影", com.calles.platform.content.domain.model.tag.TagType.DOMAIN);

        when(tagService.getDomainTags()).thenReturn(List.of(tagDom1, tagDom2));

        mockMvc.perform(get("/api/content/tags/domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("编程"))
                .andExpect(jsonPath("$.data[0].tagType").value("DOMAIN"))
                .andExpect(jsonPath("$.data[1].name").value("摄影"))
                .andExpect(jsonPath("$.data[1].tagType").value("DOMAIN"));
    }
}
