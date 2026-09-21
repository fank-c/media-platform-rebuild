package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.repository.UserBlockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserBlockApplicationService 用户屏蔽黑名单应用服务单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserBlockApplicationService 屏蔽服务测试")
class UserBlockApplicationServiceTest {

    @Mock
    private UserBlockRepository userBlockRepository;

    @InjectMocks
    private UserBlockApplicationService applicationService;

    @Test
    @DisplayName("addBlock：入参完整时创建并持久化实体")
    void shouldAddBlockSuccessfully() {
        UserBlock block = applicationService.addBlock("u1", BlockType.AUTHOR, "author_1", "内容不适");

        assertThat(block).isNotNull();
        assertThat(block.getUserId()).isEqualTo("u1");
        assertThat(block.getBlockType()).isEqualTo(BlockType.AUTHOR);
        assertThat(block.getTargetId()).isEqualTo("author_1");

        verify(userBlockRepository).save(argThat(b ->
                b.getUserId().equals("u1") && b.getTargetId().equals("author_1")
        ));
    }

    @Test
    @DisplayName("addBlock：参数缺失时抛出 IllegalArgumentException")
    void shouldThrowWhenParamsMissing() {
        assertThatThrownBy(() -> applicationService.addBlock(null, BlockType.VIDEO, "v1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> applicationService.addBlock("u1", null, "v1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> applicationService.addBlock("u1", BlockType.VIDEO, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("removeBlock：调用仓储物理/逻辑删除")
    void shouldRemoveBlock() {
        applicationService.removeBlock("u1", BlockType.VIDEO, "vid_1");
        verify(userBlockRepository).delete("u1", BlockType.VIDEO, "vid_1");
    }

    @Test
    @DisplayName("listBlocks：查询当前用户的所有屏蔽记录")
    void shouldListBlocks() {
        UserBlock b1 = UserBlock.create("u1", BlockType.VIDEO, "vid_1", null);
        when(userBlockRepository.findByUserId("u1")).thenReturn(List.of(b1));

        List<UserBlock> result = applicationService.listBlocks("u1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetId()).isEqualTo("vid_1");
    }
}
