package com.calles.platform.interaction.interfaces.http;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
import com.calles.platform.interaction.application.star.StarApplicationService;
import com.calles.platform.interaction.domain.model.star.StarFolder;
import com.calles.platform.interaction.domain.model.star.StarItem;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 视频收藏与收藏夹 HTTP 控制器单元测试。
 */
@ExtendWith(MockitoExtension.class)
class InteractionStarControllerTest {

    private MockMvc mockMvc;

    @Mock
    private StarApplicationService starService;

    @Mock
    private InteractionAccessPolicy accessPolicy;

    private final UserInfo sampleUser = new UserInfo("user_001", "USER", "user", "session_001");

    @BeforeEach
    void setUp() {
        InteractionStarController controller = new InteractionStarController(starService, accessPolicy);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InteractionExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/interactions/star/folders 获取收藏夹列表成功")
    void shouldGetFoldersSuccessfully() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        StarFolder defaultFolder = StarFolder.createDefault("user_001");
        when(starService.getUserFolders("user_001")).thenReturn(List.of(defaultFolder));

        mockMvc.perform(get("/api/interactions/star/folders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(defaultFolder.getId()))
                .andExpect(jsonPath("$.data[0].title").value("默认收藏夹"))
                .andExpect(jsonPath("$.data[0].isDefault").value(true));
    }

    @Test
    @DisplayName("POST /api/interactions/star/folders 创建自定义收藏夹成功")
    void shouldCreateFolderSuccessfully() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        StarFolder folder = StarFolder.createCustom("user_001", "技术分享");
        when(starService.createCustomFolder("user_001", "技术分享")).thenReturn(folder);

        mockMvc.perform(post("/api/interactions/star/folders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"技术分享\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(folder.getId()))
                .andExpect(jsonPath("$.data.title").value("技术分享"))
                .andExpect(jsonPath("$.data.isDefault").value(false));
    }

    @Test
    @DisplayName("POST /api/interactions/star/folders 标题为空时返回 400")
    void shouldRejectBlankTitleOnCreateFolder() throws Exception {
        mockMvc.perform(post("/api/interactions/star/folders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("PUT /api/interactions/star/folders/{folderId} 修改收藏夹标题成功")
    void shouldUpdateFolderSuccessfully() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        StarFolder folder = StarFolder.createCustom("user_001", "新标题");
        when(starService.renameFolder(eq("f_123"), eq("user_001"), eq("新标题"))).thenReturn(folder);

        mockMvc.perform(put("/api/interactions/star/folders/f_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("新标题"));
    }

    @Test
    @DisplayName("DELETE /api/interactions/star/folders/{folderId} 删除收藏夹成功")
    void shouldDeleteFolderSuccessfully() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        mockMvc.perform(delete("/api/interactions/star/folders/f_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(starService).deleteFolder("f_123", "user_001");
    }

    @Test
    @DisplayName("GET /api/interactions/star/items 分页获取明细成功")
    void shouldGetStarItemsSuccessfully() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        StarItem item = StarItem.create("f_123", "vid_999", "user_001");
        when(starService.getStarItems(eq("f_123"), eq("user_001"), anyInt(), anyInt()))
                .thenReturn(List.of(item));

        mockMvc.perform(get("/api/interactions/star/items")
                        .param("folderId", "f_123")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].vid").value("vid_999"));
    }

    @Test
    @DisplayName("POST /api/interactions/videos/{vid}/star 收藏视频成功")
    void shouldStarVideoSuccessfully() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(sampleUser);
        when(starService.starVideo("cv_100", "user_001", null)).thenReturn("item_001");

        mockMvc.perform(post("/api/interactions/videos/cv_100/star"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.vid").value("cv_100"))
                .andExpect(jsonPath("$.data.action").value("STAR"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    @DisplayName("DELETE /api/interactions/videos/{vid}/star 取消收藏成功")
    void shouldUnstarVideoSuccessfully() throws Exception {
        when(accessPolicy.requireUser()).thenReturn(sampleUser);

        mockMvc.perform(delete("/api/interactions/videos/cv_100/star"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.vid").value("cv_100"))
                .andExpect(jsonPath("$.data.action").value("UNSTAR"))
                .andExpect(jsonPath("$.data.active").value(false));

        verify(starService).unstarVideo("cv_100", "user_001", null);
    }
}
