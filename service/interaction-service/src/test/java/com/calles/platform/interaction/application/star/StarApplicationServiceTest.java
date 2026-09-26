package com.calles.platform.interaction.application.star;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.star.StarFolder;
import com.calles.platform.interaction.domain.model.star.StarItem;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.StarFolderRepository;
import com.calles.platform.interaction.domain.repository.StarItemRepository;
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
    private CounterDeltaRepository counterDeltaRepository;

    @Mock
    private com.calles.platform.interaction.application.event.InteractionEventPublisher eventPublisher;

    private StarApplicationService service;

    @BeforeEach
    void setUp() {
        service = new StarApplicationService(folderRepository, itemRepository, counterDeltaRepository, eventPublisher);
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
        verify(counterDeltaRepository).adjustStarCount(eq("vid_100"), eq("STAR_ACTIVE"), org.mockito.ArgumentMatchers.argThat(s -> s.startsWith("star_item:") && s.endsWith(":v1")), eq(1L));
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
        verify(counterDeltaRepository, never()).adjustStarCount(any(), any(), any(), eq(1L));
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
        verify(counterDeltaRepository).adjustStarCount(eq("vid_100"), eq("STAR_ACTIVE"), eq("star_item:" + deletedItem.getId() + ":v2"), eq(1L));
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("取消收藏且无其他收藏夹包含时扣减计数并发布取消收藏事件")
    void shouldUnstarVideoAndDecrementCount() {
        StarItem item = StarItem.create("f1", "vid_100", "user_01");
        when(itemRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(java.util.List.of(item));
        when(itemRepository.deleteByUserAndVid("user_01", "vid_100")).thenReturn(1);
        when(itemRepository.isStarredByUser("user_01", "vid_100")).thenReturn(false);

        service.unstarVideo("vid_100", "user_01", null);

        verify(counterDeltaRepository).adjustStarCount("vid_100", "STAR_INACTIVE", "star_unstar:" + item.getId() + ":v1", -1L);
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

        verify(counterDeltaRepository, never()).adjustStarCount(any(), any(), any(), eq(-1L));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("按不存在或非本人的收藏夹移除时抛出异常阻断越权")
    void shouldThrowWhenUnstarringFromNonExistentOrUnownedFolder() {
        when(folderRepository.findById("folder_other")).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.unstarVideo("vid_100", "user_01", "folder_other"));
    }

    @Test
    @DisplayName("尝试收藏到他人收藏夹时抛出异常阻断越权写入")
    void shouldThrowWhenStarringToOtherUserFolder() {
        StarFolder otherUserFolder = StarFolder.createCustom("user_other", "他人私密收藏");
        when(folderRepository.findById(otherUserFolder.getId())).thenReturn(Optional.of(otherUserFolder));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.starVideo("vid_100", "user_01", otherUserFolder.getId()));

        verify(itemRepository, never()).save(any());
        verify(counterDeltaRepository, never()).adjustStarCount(any(), any(), any(), any(Long.class));
    }

    @Test
    @DisplayName("尝试读取他人收藏夹明细时抛出异常阻断越权读取")
    void shouldThrowWhenGettingStarItemsFromOtherUserFolder() {
        StarFolder otherUserFolder = StarFolder.createCustom("user_other", "他人私密收藏");
        when(folderRepository.findById(otherUserFolder.getId())).thenReturn(Optional.of(otherUserFolder));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.getStarItems(otherUserFolder.getId(), "user_01", 1, 20));

        verify(itemRepository, never()).findByFolderId(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("未指定收藏夹且用户未初始化默认收藏夹时，读取明细返回空列表且不写库")
    void shouldReturnEmptyListWhenNoDefaultFolderExists() {
        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.empty());

        var items = service.getStarItems(null, "user_01", 1, 20);

        assertThat(items).isEmpty();
        verify(folderRepository, never()).save(any());
        verify(itemRepository, never()).findByFolderId(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("获取用户收藏夹列表时为纯读查询，即便为空也绝不隐式写库保存默认收藏夹")
    void shouldReturnEmptyListWithoutSavingDefaultFolderInGetUserFolders() {
        when(folderRepository.findActiveByUserId("user_01")).thenReturn(java.util.List.of());

        var folders = service.getUserFolders("user_01");

        assertThat(folders).isEmpty();
        verify(folderRepository, never()).save(any());
    }

    @Test
    @DisplayName("initDefaultFolder 在默认收藏夹不存在时持久化新建并返回")
    void shouldCreateAndSaveDefaultFolderWhenNotPresentInInitDefaultFolder() {
        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.empty());

        StarFolder folder = service.initDefaultFolder("user_01");

        assertThat(folder).isNotNull();
        assertThat(folder.isDefault()).isTrue();
        assertThat(folder.getTitle()).isEqualTo("默认收藏夹");
        verify(folderRepository).save(any(StarFolder.class));
    }

    @Test
    @DisplayName("initDefaultFolder 在默认收藏夹已存在时幂等返回已有实体且不重复写库")
    void shouldReturnExistingDefaultFolderInInitDefaultFolder() {
        StarFolder existing = StarFolder.createDefault("user_01");
        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.of(existing));

        StarFolder folder = service.initDefaultFolder("user_01");

        assertThat(folder).isSameAs(existing);
        verify(folderRepository, never()).save(any());
    }

    @Test
    @DisplayName("创建自定义收藏夹时若命名为默认收藏夹则抛出异常阻断")
    void shouldThrowWhenCreatingFolderWithDefaultTitle() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.createCustomFolder("user_01", "默认收藏夹"));

        verify(folderRepository, never()).save(any());
    }

    @Test
    @DisplayName("创建自定义收藏夹时若已存在同名活跃收藏夹则抛出异常阻断")
    void shouldThrowWhenCreatingFolderWithDuplicateTitle() {
        when(folderRepository.existsByUserIdAndTitle("user_01", "学习")).thenReturn(true);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.createCustomFolder("user_01", "学习"));

        verify(folderRepository, never()).save(any());
    }

    @Test
    @DisplayName("成功创建自定义收藏夹并持久化")
    void shouldCreateCustomFolderSuccessfully() {
        when(folderRepository.existsByUserIdAndTitle("user_01", "技术研读")).thenReturn(false);

        StarFolder folder = service.createCustomFolder("user_01", "技术研读");

        assertThat(folder).isNotNull();
        assertThat(folder.getTitle()).isEqualTo("技术研读");
        assertThat(folder.isDefault()).isFalse();
        verify(folderRepository).save(any(StarFolder.class));
    }

    @Test
    @DisplayName("重命名自定义收藏夹成功")
    void shouldRenameFolderSuccessfully() {
        StarFolder folder = StarFolder.createCustom("user_01", "旧名称");
        when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));
        when(folderRepository.existsByUserIdAndTitleExcludingId("user_01", "新名称", folder.getId())).thenReturn(false);

        StarFolder renamed = service.renameFolder(folder.getId(), "user_01", "新名称");

        assertThat(renamed.getTitle()).isEqualTo("新名称");
        verify(folderRepository).update(folder);
    }

    @Test
    @DisplayName("重命名新旧标题相同时幂等返回且不调用仓储更新")
    void shouldBeIdempotentWhenRenamingWithSameTitle() {
        StarFolder folder = StarFolder.createCustom("user_01", "同名");
        when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));

        StarFolder renamed = service.renameFolder(folder.getId(), "user_01", "同名");

        assertThat(renamed.getTitle()).isEqualTo("同名");
        verify(folderRepository, never()).update(any());
    }

    @Test
    @DisplayName("尝试重命名默认收藏夹抛出异常阻断")
    void shouldThrowWhenRenamingDefaultFolder() {
        StarFolder defaultFolder = StarFolder.createDefault("user_01");
        when(folderRepository.findById(defaultFolder.getId())).thenReturn(Optional.of(defaultFolder));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.renameFolder(defaultFolder.getId(), "user_01", "修改默认"));

        verify(folderRepository, never()).update(any());
    }

    @Test
    @DisplayName("重命名新标题与其他收藏夹冲突时抛出异常阻断")
    void shouldThrowWhenRenamingWithConflictingTitle() {
        StarFolder folder = StarFolder.createCustom("user_01", "旧名称");
        when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));
        when(folderRepository.existsByUserIdAndTitleExcludingId("user_01", "已被占用的名称", folder.getId())).thenReturn(true);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.renameFolder(folder.getId(), "user_01", "已被占用的名称"));

        verify(folderRepository, never()).update(any());
    }

    @Test
    @DisplayName("删除自定义收藏夹时级联软删除明细，对彻底失去收藏的视频扣减计数并写 Outbox")
    void shouldDeleteFolderAndCascadeItemsWithCounterDecrement() {
        StarFolder folder = StarFolder.createCustom("user_01", "待删夹");
        when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));
        when(itemRepository.findVidsByFolderId(folder.getId())).thenReturn(java.util.List.of("vid_1", "vid_2"));
        // vid_1 在其它收藏夹中已无收录，vid_2 仍被收录在默认收藏夹
        when(itemRepository.isStarredByUser("user_01", "vid_1")).thenReturn(false);
        when(itemRepository.isStarredByUser("user_01", "vid_2")).thenReturn(true);

        service.deleteFolder(folder.getId(), "user_01");

        // 验证收藏夹本身与明细均被软删除
        verify(folderRepository).deleteById(folder.getId());
        verify(itemRepository).deleteByFolderId(folder.getId());

        // vid_1 扣减计数并写 Outbox；vid_2 不触发
        verify(counterDeltaRepository).adjustStarCount("vid_1", "STAR_INACTIVE", "star_folder_del:" + folder.getId() + ":vid_1", -1L);
        verify(eventPublisher).publishVideoAction(any());
        verify(counterDeltaRepository, never()).adjustStarCount(eq("vid_2"), any(), any(), eq(-1L));
    }

    @Test
    @DisplayName("尝试删除默认收藏夹抛出异常阻断")
    void shouldThrowWhenDeletingDefaultFolder() {
        StarFolder defaultFolder = StarFolder.createDefault("user_01");
        when(folderRepository.findById(defaultFolder.getId())).thenReturn(Optional.of(defaultFolder));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.deleteFolder(defaultFolder.getId(), "user_01"));

        verify(folderRepository, never()).deleteById(any());
        verify(itemRepository, never()).deleteByFolderId(any());
    }

    @Test
    @DisplayName("空白或空串 folderId 改名请求直接拒绝且绝无写库自愈创建默认收藏夹的副作用")
    void shouldRejectBlankFolderIdOnRenameWithoutSideEffects() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.renameFolder("   ", "user_01", "新名称"));

        verify(folderRepository, never()).save(any());
        verify(folderRepository, never()).update(any());
    }

    @Test
    @DisplayName("空白或空串 folderId 删除请求直接拒绝且绝无写库自愈创建默认收藏夹的副作用")
    void shouldRejectBlankFolderIdOnDeleteWithoutSideEffects() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.deleteFolder("", "user_01"));

        verify(folderRepository, never()).save(any());
        verify(folderRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("创建自定义收藏夹遭遇数据库唯一键冲突并发异常时转换为友好业务提示")
    void shouldConvertDuplicateKeyExceptionOnCreateFolder() {
        when(folderRepository.existsByUserIdAndTitle("user_01", "并发竞态夹")).thenReturn(false);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry"))
                .when(folderRepository).save(any());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.createCustomFolder("user_01", "并发竞态夹"));
    }

    @Test
    @DisplayName("修改收藏夹标题遭遇数据库唯一键冲突并发异常时转换为友好业务提示")
    void shouldConvertDuplicateKeyExceptionOnRenameFolder() {
        StarFolder folder = StarFolder.createCustom("user_01", "原名称");
        when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));
        when(folderRepository.existsByUserIdAndTitleExcludingId("user_01", "新名称", folder.getId())).thenReturn(false);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry"))
                .when(folderRepository).update(any());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.renameFolder(folder.getId(), "user_01", "新名称"));
    }

    @Test
    @DisplayName("并发初始化默认收藏夹触发唯一键冲突时：采用当前读(FOR UPDATE)穿透MVCC并幂等读取胜出记录")
    void shouldFallbackWhenDuplicateKeyOccursInInitDefaultFolder() {
        StarFolder existing = StarFolder.createDefault("user_01");
        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.empty());
        when(folderRepository.findDefaultByUserIdForUpdate("user_01")).thenReturn(Optional.of(existing));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry"))
                .when(folderRepository).save(any());

        StarFolder folder = service.initDefaultFolder("user_01");

        assertThat(folder).isNotNull();
        assertThat(folder.getId()).isEqualTo(existing.getId());
        verify(folderRepository).findDefaultByUserIdForUpdate("user_01");
    }

    @Test
    @DisplayName("并发初始化唯一键冲突且当前读依然未能查到时抛出 IllegalStateException")
    void shouldThrowWhenCurrentReadFailsInInitDefaultFolder() {
        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.empty());
        when(folderRepository.findDefaultByUserIdForUpdate("user_01")).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry"))
                .when(folderRepository).save(any());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                service.initDefaultFolder("user_01"));
    }

    @Test
    @DisplayName("写 Outbox 异常导致事务回滚时：afterCommit 不执行，Redis 收藏计数绝对不被扣减")
    void shouldNotAdjustStarCountWhenTransactionRollsBackDueToOutboxFailureInDeleteFolder() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            StarFolder folder = StarFolder.createCustom("user_01", "待删夹");
            when(folderRepository.findById(folder.getId())).thenReturn(Optional.of(folder));
            when(itemRepository.findVidsByFolderId(folder.getId())).thenReturn(java.util.List.of("vid_1"));
            when(itemRepository.isStarredByUser("user_01", "vid_1")).thenReturn(false);

            org.mockito.Mockito.doThrow(new RuntimeException("Outbox 落库失败"))
                    .when(eventPublisher).publishVideoAction(any());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deleteFolder(folder.getId(), "user_01"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Outbox 落库失败");

            // 关键断言：事务失败回滚，afterCommit 绝不执行，Redis 收藏计数绝不扣减
            verify(counterDeltaRepository, never()).adjustStarCount(any(), any(), any(), any(Long.class));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("增量记录写入失败时业务操作失败，不会执行数据库计数补偿")
    void shouldFailStarWhenDeltaInsertFails() {
        StarFolder defaultFolder = StarFolder.createDefault("user_01");
        when(folderRepository.findDefaultByUserId("user_01")).thenReturn(Optional.of(defaultFolder));
        when(itemRepository.findPhysicalByFolderAndVid(defaultFolder.getId(), "vid_100")).thenReturn(Optional.empty());
        when(itemRepository.isStarredByUser("user_01", "vid_100")).thenReturn(false);
        org.mockito.Mockito.doThrow(new RuntimeException("增量插入失败"))
                .when(counterDeltaRepository).adjustStarCount(eq("vid_100"), eq("STAR_ACTIVE"), any(), eq(1L));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.starVideo("vid_100", "user_01", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("增量插入失败");
        verify(counterDeltaRepository).adjustStarCount(eq("vid_100"), eq("STAR_ACTIVE"), any(), eq(1L));
    }
}
