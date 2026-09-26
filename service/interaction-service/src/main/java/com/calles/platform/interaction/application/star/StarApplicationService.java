package com.calles.platform.interaction.application.star;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.star.StarFolder;
import com.calles.platform.interaction.domain.model.star.StarItem;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.StarFolderRepository;
import com.calles.platform.interaction.domain.repository.StarItemRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
    private final CounterDeltaRepository counterDeltaRepository;
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
        // 步骤 1: 确定归属收藏夹（显式指定时严格校验属主与可用性；未指定时按需自愈初始化默认收藏夹）
        StarFolder folder;
        if (targetFolderId != null && !targetFolderId.isBlank()) {
            folder = requireOwnedFolder(userId, targetFolderId);
        } else {
            folder = initDefaultFolder(userId);
        }

        // 步骤 2: 校验该收藏夹内是否已收录该视频（物理检索，兼容伪删除自愈并规避 uk_folder_vid 冲突）
        Optional<StarItem> physicalItem = itemRepository.findPhysicalByFolderAndVid(folder.getId(), vid);
        if (physicalItem.isPresent()) {
            StarItem item = physicalItem.get();
            if (!item.isDeleted()) {
                log.debug("用户 [{}] 在收藏夹 [{}] 已收藏过视频 [{}]，幂等跳过", userId, folder.getId(), vid);
                return item.getId();
            }
            // 步骤 2.1: 若该条目此前已被伪删除，则执行复活；用户此前未收藏时同事务记录计数增量和 Outbox
            boolean alreadyStarred = itemRepository.isStarredByUser(userId, vid);
            item.revive();
            itemRepository.revive(item.getId());
            if (!alreadyStarred) {
                eventPublisher.publishVideoAction(VideoActionPayload.star(userId, vid));
                counterDeltaRepository.adjustStarCount(vid, "STAR_ACTIVE", "star_item:" + item.getId() + ":v" + item.getVersion(), 1L);
                log.info("用户 [{}] 首次收藏视频 [{}] (复活原有明细)，写入 Outbox 与计数增量 (version={})", userId, vid, item.getVersion());
            }
            return item.getId();
        }

        // 步骤 3: 检查此操作前用户是否已在任何收藏夹收藏过该视频
        boolean alreadyStarred = itemRepository.isStarredByUser(userId, vid);

        // 步骤 4: 保存收藏条目
        StarItem newItem = StarItem.create(folder.getId(), vid, userId);
        itemRepository.save(newItem);

        // 步骤 5: 若为该用户对该视频的首度收藏，同事务写 Outbox 与待汇总增量
        if (!alreadyStarred) {
            eventPublisher.publishVideoAction(VideoActionPayload.star(userId, vid));
            counterDeltaRepository.adjustStarCount(vid, "STAR_ACTIVE", "star_item:" + newItem.getId() + ":v" + newItem.getVersion(), 1L);
            log.info("用户 [{}] 首次收藏视频 [{}]，写入 Outbox 与计数增量 (version={})", userId, vid, newItem.getVersion());
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
        // 步骤 0: 预查本次操作涉及的收藏明细（用于精确定界状态版本）
        List<StarItem> itemsBeforeDelete = itemRepository.findByUserAndVid(userId, vid);

        int deleted;
        // 步骤 1: 根据是否指定特定收藏夹执行明细删除（显式指定时先严格校验属主与可用性）
        if (folderId != null && !folderId.isBlank()) {
            requireOwnedFolder(userId, folderId);
            deleted = itemRepository.deleteByFolderAndVidAndUser(folderId.trim(), vid, userId);
        } else {
            deleted = itemRepository.deleteByUserAndVid(userId, vid);
        }

        // 步骤 2: 实际删除且其它收藏夹已无此视频时，同事务写 Outbox 与待汇总增量
        if (deleted > 0) {
            boolean stillStarred = itemRepository.isStarredByUser(userId, vid);
            if (!stillStarred) {
                String sourceId;
                if (!itemsBeforeDelete.isEmpty()) {
                    StarItem lastItem = itemsBeforeDelete.get(itemsBeforeDelete.size() - 1);
                    sourceId = "star_unstar:" + lastItem.getId() + ":v" + lastItem.getVersion();
                } else {
                    sourceId = "star_unstar:" + userId + ":" + vid;
                }
                eventPublisher.publishVideoAction(VideoActionPayload.unstar(userId, vid));
                counterDeltaRepository.adjustStarCount(vid, "STAR_INACTIVE", sourceId, -1L);
                log.info("用户 [{}] 完全取消收藏视频 [{}]，写入 Outbox 与计数增量 (sourceId={})", userId, vid, sourceId);
            }
        }
    }

    /**
     * 获取用户所有可用收藏夹列表（纯读查询，不产生写库副作用）。
     *
     * @param userId 用户账号 ID
     * @return 正常可用的收藏夹列表，若无则返回空列表
     */
    public List<StarFolder> getUserFolders(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        // 步骤 1: 仅查询活跃收藏夹，空列表直接返回，绝不隐式写库
        return folderRepository.findActiveByUserId(userId.trim());
    }

    /**
     * 为指定用户初始化默认收藏夹（幂等）。
     *
     * <p>若该用户已存在可用默认收藏夹则直接返回；若不存在则持久化新建默认收藏夹。
     * 捕获底层并发唯一键冲突并安全回退读取胜出记录，供首次收藏时按需调用，
     * 或供后续用户注册/初始化领域事件消费者直接调用。</p>
     *
     * @param userId 用户账号 ID
     * @return 默认收藏夹实体
     */
    @Transactional(rollbackFor = Exception.class)
    public StarFolder initDefaultFolder(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户账号ID不能为空");
        }
        String safeUserId = userId.trim();
        // 步骤 1: 检查是否已存在活跃的默认收藏夹，避免重复初始化
        return folderRepository.findDefaultByUserId(safeUserId).orElseGet(() -> {
            try {
                // 步骤 2: 不存在时持久化新建默认收藏夹
                StarFolder created = StarFolder.createDefault(safeUserId);
                folderRepository.save(created);
                log.info("为用户 [{}] 初始化创建了默认收藏夹 [{}] (id={})", safeUserId, created.getTitle(), created.getId());
                return created;
            } catch (DuplicateKeyException e) {
                // 步骤 2.1: 并发初始化唯一键冲突时，采用当前读 (FOR UPDATE) 穿透 MVCC 快照，回退读取已由胜出线程成功落库的默认收藏夹
                log.warn("用户 [{}] 并发初始化默认收藏夹冲突，使用当前读回退读取已有实体", safeUserId);
                return folderRepository.findDefaultByUserIdForUpdate(safeUserId)
                        .orElseThrow(() -> new IllegalStateException("默认收藏夹创建冲突且当前读回退失败", e));
            }
        });
    }

    /**
     * 创建用户自定义收藏夹。
     *
     * @param userId 用户 ID
     * @param title 收藏夹标题 (不能为“默认收藏夹”，且不能与既有活跃收藏夹重名)
     * @return 新建收藏夹实体
     */
    @Transactional(rollbackFor = Exception.class)
    public StarFolder createCustomFolder(String userId, String title) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户账号ID不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("收藏夹标题不能为空");
        }
        String trimmedTitle = title.trim();
        // 步骤 1: 约束校验——禁止使用系统保留的默认收藏夹名称
        if (StarFolder.DEFAULT_FOLDER_TITLE.equals(trimmedTitle)) {
            throw new IllegalArgumentException("不能使用系统默认收藏夹名称");
        }
        // 步骤 2: 重名预检查——同一用户下活跃状态收藏夹不可重名
        if (folderRepository.existsByUserIdAndTitle(userId.trim(), trimmedTitle)) {
            throw new IllegalArgumentException("已存在同名收藏夹");
        }

        // 步骤 3: 实例化并持久化保存，底层唯一键防并发竞态兜底
        try {
            StarFolder folder = StarFolder.createCustom(userId, trimmedTitle);
            folderRepository.save(folder);
            log.info("用户 [{}] 创建了自定义收藏夹 [{}] (id={})", userId, trimmedTitle, folder.getId());
            return folder;
        } catch (DuplicateKeyException e) {
            log.warn("用户 [{}] 并发创建收藏夹 [{}] 触发唯一键冲突", userId, trimmedTitle);
            throw new IllegalArgumentException("已存在同名收藏夹");
        }
    }

    /**
     * 修改用户自定义收藏夹标题。
     *
     * @param folderId 待修改的收藏夹 ID
     * @param userId 操作用户 ID
     * @param newTitle 新标题名称 (不能为“默认收藏夹”，且不能与既有活跃收藏夹重名)
     * @return 修改后的收藏夹实体
     */
    @Transactional(rollbackFor = Exception.class)
    public StarFolder renameFolder(String folderId, String userId, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("新标题不能为空");
        }
        String trimmedTitle = newTitle.trim();
        // 步骤 1: 约束校验——禁止更名为系统保留默认收藏夹名称
        if (StarFolder.DEFAULT_FOLDER_TITLE.equals(trimmedTitle)) {
            throw new IllegalArgumentException("不能使用系统默认收藏夹名称");
        }

        // 步骤 2: 严格解析显式指定的有效收藏夹（校验 ID 非空、存在且属于当前用户，绝不隐式写库创建默认夹）
        StarFolder folder = requireOwnedFolder(userId, folderId);

        // 步骤 3: 默认收藏夹不可更名业务约束
        if (folder.isDefault()) {
            throw new IllegalArgumentException("默认收藏夹不可更名");
        }

        // 步骤 4: 若新旧标题一致则幂等返回，无需额外查重与更新
        if (folder.getTitle().equals(trimmedTitle)) {
            return folder;
        }

        // 步骤 5: 重名预检查——排查当前用户名下其他活跃收藏夹是否已占用该新标题
        if (folderRepository.existsByUserIdAndTitleExcludingId(userId, trimmedTitle, folder.getId())) {
            throw new IllegalArgumentException("已存在同名收藏夹");
        }

        // 步骤 6: 实体更名并持久化更新，底层唯一键防并发冲突兜底
        try {
            folder.rename(trimmedTitle);
            folderRepository.update(folder);
            log.info("用户 [{}] 将收藏夹 [{}] 重命名为 [{}]", userId, folderId, trimmedTitle);
            return folder;
        } catch (DuplicateKeyException e) {
            log.warn("用户 [{}] 并发重命名收藏夹 [{}] 为 [{}] 触发唯一键冲突", userId, folderId, trimmedTitle);
            throw new IllegalArgumentException("已存在同名收藏夹");
        }
    }

    /**
     * 删除用户自定义收藏夹（含明细级联清理与视频彻底移出联动计数）。
     *
     * @param folderId 待删除的收藏夹 ID
     * @param userId 操作用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFolder(String folderId, String userId) {
        // 步骤 1: 严格解析显式指定的有效收藏夹（校验 ID 非空、存在且属于当前用户，绝不隐式写库）
        StarFolder folder = requireOwnedFolder(userId, folderId);

        // 步骤 2: 默认收藏夹不可删除业务约束
        if (folder.isDefault()) {
            throw new IllegalArgumentException("默认收藏夹不可删除");
        }

        // 步骤 3: 提取该收藏夹内所有未删除的视频 vid（去重），以便后续判断是否彻底取消收藏
        List<String> affectedVids = itemRepository.findVidsByFolderId(folder.getId());

        // 步骤 4: 逻辑删除收藏夹自身
        folder.delete();
        folderRepository.deleteById(folder.getId());

        // 步骤 5: 级联逻辑删除该收藏夹内的所有收藏明细条目
        itemRepository.deleteByFolderId(folder.getId());

        // 步骤 6: 状态与计数联动——逐一校验受影响视频在用户其它有效收藏夹中是否仍被收录
        for (String vid : affectedVids) {
            boolean stillStarred = itemRepository.isStarredByUser(userId, vid);
            if (!stillStarred) {
                eventPublisher.publishVideoAction(VideoActionPayload.unstar(userId, vid));
                counterDeltaRepository.adjustStarCount(vid, "STAR_INACTIVE", "star_folder_del:" + folder.getId() + ":" + vid, -1L);
                log.info("用户 [{}] 因删除收藏夹 [{}] 彻底移出视频 [{}]，写入 Outbox 与计数增量", userId, folderId, vid);
            }
        }
        log.info("用户 [{}] 成功删除了自定义收藏夹 [{}] (id={})", userId, folder.getTitle(), folderId);
    }

    /**
     * 分页查询指定收藏夹内的视频明细列表。
     *
     * @param folderId 收藏夹 ID (可选，为空时获取默认收藏夹)
     * @param userId 用户账号 ID
     * @param page 页码 (从 1 起始)
     * @param size 每页容量
     * @return 明细条目列表
     */
    public List<StarItem> getStarItems(String folderId, String userId, int page, int size) {
        String effectiveFolderId;
        // 步骤 1: 若显式指定 folderId，严格执行属主与存在性校验（阻断越权窥探他人收藏夹）
        if (folderId != null && !folderId.isBlank()) {
            StarFolder folder = requireOwnedFolder(userId, folderId);
            effectiveFolderId = folder.getId();
        } else {
            // 步骤 2: 若未指定 folderId，查默认收藏夹；若用户从未收藏过导致默认收藏夹尚未建立，直接返回空（纯读不写库）
            effectiveFolderId = folderRepository.findDefaultByUserId(userId)
                    .map(StarFolder::getId)
                    .orElse(null);
        }

        if (effectiveFolderId == null) {
            return List.of();
        }

        // 步骤 3: 规整分页参数并执行明细查询
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
     * 严格解析显式指定的有效收藏夹：校验 ID 非空、存在且属于当前用户，绝不隐式创建默认收藏夹。
     *
     * @param userId 操作用户 ID
     * @param folderId 收藏夹 ID (必填)
     * @return 目标收藏夹实体
     * @throws IllegalArgumentException 当 folderId 为空、收藏夹不存在、已删除或非当前用户所有时
     */
    private StarFolder requireOwnedFolder(String userId, String folderId) {
        if (folderId == null || folderId.isBlank()) {
            throw new IllegalArgumentException("收藏夹ID不能为空");
        }
        return folderRepository.findById(folderId.trim())
                .filter(folder -> folder.isActive() && folder.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("指定的收藏夹不存在或已删除"));
    }

}
