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

    @Mock
    private com.calles.platform.interaction.application.event.InteractionEventPublisher eventPublisher;

    private StarApplicationService service;

    @BeforeEach
    void setUp() {
        service = new StarApplicationService(folderRepository, itemRepository, counterRepository, eventPublisher);
    }

    @Test
    @DisplayName("首次收藏视频自动创建默认收藏夹并递增计数")
    void shouldStarVideoAndCreateDefaultFolder() {
        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.empty());
        when(itemRepository.findPhysicalByFolderAndVid(any(), eq("vid_100"))).thenReturn(Optional.empty());
        when(itemRepository.isStarredByUser("user_01", "vid_100")).thenReturn(false);

        String itemId = service.starVideo("vid_100", "user_01", null);

        assertThat(itemId).isNotBlank();
        verify(folderRepository).save(any(StarFolder.class));
        verify(itemRepository).save(any(StarItem.class));
        verify(counterRepository).adjustStarCount("vid_100", 1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("同一收藏夹重复收藏幂等且不重复自增计数与发布事件")
    void shouldBeIdempotentWhenAlreadyStarredInFolder() {
        StarFolder folder = StarFolder.createDefault("user_01");
        StarItem existing = StarItem.create(folder.getId(), "vid_100", "user_01");

        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.of(folder));
        when(itemRepository.findPhysicalByFolderAndVid(folder.getId(), "vid_100")).thenReturn(Optional.of(existing));

        String itemId = service.starVideo("vid_100", "user_01", null);

        assertThat(itemId).isEqualTo(existing.getId());
        verify(itemRepository, never()).save(any());
        verify(counterRepository, never()).adjustStarCount(any(), eq(1L));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("此前伪删除的收藏条目重新收藏时自愈复活并正常触发计数与事件")
    void shouldReviveSoftDeletedStarItemWhenReStarred() {
        StarFolder folder = StarFolder.createDefault("user_01");
        StarItem deletedItem = StarItem.create(folder.getId(), "vid_100", "user_01");
        deletedItem.markDeleted(); // 标记伪删除
        assertThat(deletedItem.isDeleted()).isTrue();

        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.of(folder));
        when(itemRepository.findPhysicalByFolderAndVid(folder.getId(), "vid_100")).thenReturn(Optional.of(deletedItem));
        when(itemRepository.isStarredByUser("user_01", "vid_100")).thenReturn(false);

        String itemId = service.starVideo("vid_100", "user_01", null);

        assertThat(itemId).isEqualTo(deletedItem.getId());
        verify(itemRepository).revive(deletedItem.getId()); // 调用自愈复活
        verify(itemRepository, never()).save(any()); // 避免重复 INSERT 触发唯一索引冲突
        verify(counterRepository).adjustStarCount("vid_100", 1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("取消收藏且无其他收藏夹包含时扣减计数并发布取消收藏事件")
    void shouldUnstarVideoAndDecrementCount() {
        when(itemRepository.deleteByUserAndVid("user_01", "vid_100")).thenReturn(1);
        when(itemRepository.isStarredByUser("user_01", "vid_100")).thenReturn(false);

        service.unstarVideo("vid_100", "user_01", null);

        verify(counterRepository).adjustStarCount("vid_100", -1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("从某一收藏夹移出但仍有其他收藏夹收录时，不扣减计数且不发布取消收藏事件")
    void shouldNotDecrementCountWhenStillStarredInOtherFolders() {
        StarFolder folder = StarFolder.createCustom("user_01", "学习");
        when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));
        when(itemRepository.deleteByFolderAndVidAndUser(folder.getId(), "vid_100", "user_01")).thenReturn(1);
        when(itemRepository.isStarredByUser("user_01", "vid_100")).thenReturn(true);

        service.unstarVideo("vid_100", "user_01", folder.getId());

        verify(counterRepository, never()).adjustStarCount(any(), eq(-1L));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("按不存在或非本人的收藏夹移除时抛出异常阻断越权")
    void shouldThrowWhenUnstarringFromNonExistentOrUnownedFolder() {
        when(folderRepository.findById("folder_other")).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.unstarVideo("vid_100", "user_01", "folder_other"));
    }
}
