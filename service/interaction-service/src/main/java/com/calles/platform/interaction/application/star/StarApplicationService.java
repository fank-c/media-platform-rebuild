package com.calles.platform.interaction.application.star;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.star.StarFolder;
import com.calles.platform.interaction.domain.model.star.StarItem;
import com.calles.platform.interaction.domain.repository.StarFolderRepository;
import com.calles.platform.interaction.domain.repository.StarItemRepository;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频收藏与收藏夹管理应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StarApplicationService {

    private final StarFolderRepository folderRepository;
    private final StarItemRepository itemRepository;
    private final VideoCounterRepository counterRepository;
    private final InteractionEventPublisher eventPublisher;

    /**
     * 收藏视频到指定收藏夹或默认收藏夹（幂等）。
     *
     * @param vid 视频公开编码
     * @param userId 用户 ID
     * @param targetFolderId 目标收藏夹 ID (可选，为空时归入默认收藏夹)
     * @return 收藏记录明细 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public String starVideo(String vid, String userId, String targetFolderId) {
        // 步骤 1: 确定归属收藏夹（若未指定或默认不存在则自愈创建默认收藏夹，同时校验所有权）
        StarFolder folder = resolveFolder(userId, targetFolderId);

        // 步骤 2: 校验该收藏夹内是否已收录该视频（物理检索，兼容伪删除自愈并规避 uk_folder_vid 冲突）
        Optional<StarItem> physicalItem = itemRepository.findPhysicalByFolderAndVid(folder.getId(), vid);
        if (physicalItem.isPresent()) {
            StarItem item = physicalItem.get();
            if (!item.isDeleted()) {
                log.debug("用户 [{}] 在收藏夹 [{}] 已收藏过视频 [{}]，幂等跳过", userId, folder.getId(), vid);
                return item.getId();
            }
            // 步骤 2.1: 若该条目此前已被伪删除，则执行复活；若用户此时全局未收藏此视频，则自增计数并写 Outbox
            boolean alreadyStarred = itemRepository.isStarredByUser(userId, vid);
            itemRepository.revive(item.getId());
            if (!alreadyStarred) {
                counterRepository.adjustStarCount(vid, 1L);
                eventPublisher.publishVideoAction(VideoActionPayload.star(userId, vid));
                log.info("用户 [{}] 首次收藏视频 [{}] (复活原有明细)，自增收藏计数并写入 Outbox", userId, vid);
            }
            return item.getId();
        }

        // 步骤 3: 检查此操作前用户是否已在任何收藏夹收藏过该视频
        boolean alreadyStarred = itemRepository.isStarredByUser(userId, vid);

        // 步骤 4: 保存收藏条目
        StarItem newItem = StarItem.create(folder.getId(), vid, userId);
        itemRepository.save(newItem);

        // 步骤 5: 若为该用户对该视频的首度收藏，递增视频总收藏数并同事务写 Outbox
        if (!alreadyStarred) {
            counterRepository.adjustStarCount(vid, 1L);
            eventPublisher.publishVideoAction(VideoActionPayload.star(userId, vid));
            log.info("用户 [{}] 首次收藏视频 [{}]，自增收藏计数并写入 Outbox", userId, vid);
        }
        return newItem.getId();
    }

    /**
     * 取消收藏视频。
     *
     * @param vid 视频公开编码
     * @param userId 用户 ID
     * @param folderId 指定收藏夹 ID (可选，为空时移出所有收藏夹)
     */
    @Transactional(rollbackFor = Exception.class)
    public void unstarVideo(String vid, String userId, String folderId) {
        int deleted;
        // 步骤 1: 根据是否指定特定收藏夹执行明细删除（先校验用户归属，防止越权）
        if (folderId != null && !folderId.isBlank()) {
            resolveFolder(userId, folderId); // 校验收藏夹是否存在且属于当前用户
            deleted = itemRepository.deleteByFolderAndVidAndUser(folderId.trim(), vid, userId);
        } else {
            deleted = itemRepository.deleteByUserAndVid(userId, vid);
        }

        // 步骤 2: 若实际删除了记录，且用户在其它收藏夹中已完全无此视频，扣减收藏总计数并同事务写 Outbox
        if (deleted > 0) {
            boolean stillStarred = itemRepository.isStarredByUser(userId, vid);
            if (!stillStarred) {
                counterRepository.adjustStarCount(vid, -1L);
                eventPublisher.publishVideoAction(VideoActionPayload.unstar(userId, vid));
                log.info("用户 [{}] 完全取消收藏视频 [{}]，扣减收藏计数并写入 Outbox", userId, vid);
            }
        }
    }

    /**
     * 获取用户所有可用收藏夹（首度访问若无收藏夹则自动初始化默认收藏夹）。
     *
     * @param userId 用户账号 ID
     * @return 收藏夹列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<StarFolder> getUserFolders(String userId) {
        List<StarFolder> folders = folderRepository.findActiveByUserId(userId);
        if (folders.isEmpty()) {
            StarFolder defaultFolder = StarFolder.createDefault(userId);
            folderRepository.save(defaultFolder);
            return List.of(defaultFolder);
        }
        return folders;
    }

    /**
     * 创建用户自定义收藏夹。
     *
     * @param userId 用户 ID
     * @param title 收藏夹标题
     * @return 新建收藏夹实体
     */
    @Transactional(rollbackFor = Exception.class)
    public StarFolder createCustomFolder(String userId, String title) {
        StarFolder folder = StarFolder.createCustom(userId, title);
        folderRepository.save(folder);
        log.info("用户 [{}] 创建了自定义收藏夹 [{}] (id={})", userId, title, folder.getId());
        return folder;
    }

    /**
     * 分页查询收藏夹内视频明细列表。
     *
     * @param folderId 收藏夹 ID (可选，为空时获取默认收藏夹)
     * @param userId 用户账号 ID
     * @param page 页码 (从 1 起始)
     * @param size 每页容量
     * @return 明细条目列表
     */
    public List<StarItem> getStarItems(String folderId, String userId, int page, int size) {
        String effectiveFolderId = folderId;
        if (effectiveFolderId == null || effectiveFolderId.isBlank()) {
            effectiveFolderId = folderRepository.findDefaultByUserId(userId)
                    .map(StarFolder::getId)
                    .orElse("");
        }
        if (effectiveFolderId.isBlank()) {
            return List.of();
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = (safePage - 1) * safeSize;
        return itemRepository.findByFolderId(effectiveFolderId, offset, safeSize);
    }

    /**
     * 判断用户是否已收藏某视频。
     *
     * @param vid 视频业务编码
     * @param userId 用户 ID
     * @return true 若已收藏
     */
    public boolean isStarred(String vid, String userId) {
        return itemRepository.isStarredByUser(userId, vid);
    }

    /**
     * 辅助解析目标收藏夹，不存在时自动初始化。
     */
    private StarFolder resolveFolder(String userId, String folderId) {
        if (folderId != null && !folderId.isBlank()) {
            return folderRepository.findById(folderId.trim())
                    .filter(StarFolder::isActive)
                    .orElseThrow(() -> new IllegalArgumentException("指定的收藏夹不存在或已删除"));
        }
        return folderRepository.findDefaultByUserId(userId).orElseGet(() -> {
            StarFolder created = StarFolder.createDefault(userId);
            folderRepository.save(created);
            return created;
        });
    }
}
