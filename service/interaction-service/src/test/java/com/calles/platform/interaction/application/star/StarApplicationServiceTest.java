package com.calles.platform.interaction.application.star;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.star.StarFolder;
import com.calles.platform.interaction.domain.model.star.StarItem;
import com.calles.platform.interaction.domain.repository.StarFolderRepository;
import com.calles.platform.interaction.domain.repository.StarItemRepository;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StarApplicationServiceTest {

    @Mock
    private StarFolderRepository folderRepository;

    @Mock
    private StarItemRepository itemRepository;

    @Mock
    private VideoCounterRepository counterRepository;

    private StarApplicationService service;

    @BeforeEach
    void setUp() {
        service = new StarApplicationService(folderRepository, itemRepository, counterRepository);
    }

    @Test
    @DisplayName("首次收藏视频自动创建默认收藏夹并递增计数")
    void shouldStarVideoAndCreateDefaultFolder() {
        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.empty());
        when(itemRepository.findByFolderAndVid(any(), eq("vid_100"))).thenReturn(Optional.empty());
        when(itemRepository.isStarredByUser("user_01", "vid_100")).thenReturn(false);

        String itemId = service.starVideo("vid_100", "user_01", null);

        assertThat(itemId).isNotBlank();
        verify(folderRepository).save(any(StarFolder.class));
        verify(itemRepository).save(any(StarItem.class));
        verify(counterRepository).adjustStarCount("vid_100", 1L);
    }

    @Test
    @DisplayName("同一收藏夹重复收藏幂等且不重复自增计数")
    void shouldBeIdempotentWhenAlreadyStarredInFolder() {
        StarFolder folder = StarFolder.createDefault("user_01");
        StarItem existing = StarItem.create(folder.getId(), "vid_100", "user_01");

        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.of(folder));
        when(itemRepository.findByFolderAndVid(folder.getId(), "vid_100")).thenReturn(Optional.of(existing));

        String itemId = service.starVideo("vid_100", "user_01", null);

        assertThat(itemId).isEqualTo(existing.getId());
        verify(itemRepository, never()).save(any());
        verify(counterRepository, never()).adjustStarCount(any(), eq(1L));
    }

    @Test
    @DisplayName("取消收藏且无其他收藏夹包含时扣减计数")
    void shouldUnstarVideoAndDecrementCount() {
        when(itemRepository.deleteByUserAndVid("user_01", "vid_100")).thenReturn(1);
        when(itemRepository.isStarredByUser("user_01", "vid_100")).thenReturn(false);

        service.unstarVideo("vid_100", "user_01", null);

        verify(counterRepository).adjustStarCount("vid_100", -1L);
    }
}
