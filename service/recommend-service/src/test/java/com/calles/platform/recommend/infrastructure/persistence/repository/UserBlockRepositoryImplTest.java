package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.infrastructure.persistence.entity.UserBlockPO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.UserBlockMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UserBlockRepositoryImpl 仓储实现单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserBlockRepositoryImpl 仓储实现测试")
class UserBlockRepositoryImplTest {

    @Mock
    private UserBlockMapper userBlockMapper;

    @InjectMocks
    private UserBlockRepositoryImpl repository;

    @Test
    @DisplayName("save：不存在既有屏蔽时正常插入")
    void shouldInsertWhenNotExists() {
        UserBlock block = UserBlock.create("u_100", BlockType.AUTHOR, "auth_1", "dislike");
        when(userBlockMapper.selectOne(any())).thenReturn(null);
        when(userBlockMapper.insert(any(UserBlockPO.class))).thenReturn(1);

        repository.save(block);

        verify(userBlockMapper).insert(any(UserBlockPO.class));
    }

    @Test
    @DisplayName("save：已存在相同屏蔽时幂等跳过插入")
    void shouldSkipWhenAlreadyExists() {
        UserBlock block = UserBlock.create("u_100", BlockType.AUTHOR, "auth_1", "dislike");
        UserBlockPO po = new UserBlockPO();
        po.setId("exist_001");
        when(userBlockMapper.selectOne(any())).thenReturn(po);

        repository.save(block);

        verify(userBlockMapper, never()).insert(any(UserBlockPO.class));
    }

    @Test
    @DisplayName("delete：正常触发删除条件")
    void shouldDeleteBlock() {
        when(userBlockMapper.delete(any())).thenReturn(1);

        repository.delete("u_100", BlockType.AUTHOR, "auth_1");

        verify(userBlockMapper).delete(any());
    }

    @Test
    @DisplayName("isBlocked：命中存在性判定")
    void shouldCheckIsBlocked() {
        when(userBlockMapper.selectCount(any())).thenReturn(1L);

        boolean blocked = repository.isBlocked("u_100", BlockType.VIDEO, "v_123");

        assertThat(blocked).isTrue();
    }

    @Test
    @DisplayName("findByUserId：正常查询用户屏蔽实体列表")
    void shouldFindByUserId() {
        UserBlockPO po = new UserBlockPO();
        po.setId("b_001");
        po.setUserId("u_100");
        po.setBlockType("TOPIC");
        po.setTargetId("tag_456");
        po.setReason("offensive");
        po.setCreatedAt(LocalDateTime.now());

        when(userBlockMapper.selectList(any())).thenReturn(List.of(po));

        List<UserBlock> blocks = repository.findByUserId("u_100");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getBlockType()).isEqualTo(BlockType.TOPIC);
        assertThat(blocks.get(0).getTargetId()).isEqualTo("tag_456");
    }
}
