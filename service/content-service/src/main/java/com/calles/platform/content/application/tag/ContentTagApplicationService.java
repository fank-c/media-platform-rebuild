package com.calles.platform.content.application.tag;

import com.calles.platform.content.domain.model.tag.ContentTag;
import com.calles.platform.content.domain.model.tag.VideoTagRel;
import com.calles.platform.content.domain.repository.ContentTagRepository;
import com.calles.platform.content.domain.repository.VideoTagRelRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容标签应用服务。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：负责轻量标签全站字典的维护、视频与标签双向关联对齐、引用计数热度统计及热门标签检索；</li>
 *   <li><b>协作对象</b>：协同 {@link ContentTagRepository} 与 {@link VideoTagRelRepository}；</li>
 *   <li><b>数据一致性保证</b>：在本地事务中完成差异比对 (Diff)，确保打标关联变更与字典热度计数严格原子同步。</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ContentTagApplicationService {

    /** 标签全局字典持久化仓储。 */
    private final ContentTagRepository contentTagRepository;

    /** 视频-标签多对多关联关系仓储。 */
    private final VideoTagRelRepository videoTagRelRepository;

    /**
     * 将逗号分隔的轻量标签文本全量同步至全局标签字典，并自动维护多对多关联与热度引用计数。
     *
     * @param videoId 关联的视频内部全局主键 ID
     * @param rawTags 逗号分隔的标签字符串 (例如 "Java,微服务,SpringCloud")
     */
    @Transactional
    public void syncVideoTags(String videoId, String rawTags) {
        if (videoId == null || videoId.isBlank()) {
            return;
        }

        // 步骤 1：解析并标准化新标签集合（去重、去除空串、统一中英文逗号）
        Set<String> newTagNames = parseTagNames(rawTags);

        // 步骤 2：加载该视频当前已持久化的关联标签记录与名称映射
        List<String> currentTagIds = videoTagRelRepository.findTagIdsByVideoId(videoId);
        List<ContentTag> currentTags = contentTagRepository.findByIds(currentTagIds);
        Map<String, ContentTag> currentTagMap = currentTags.stream()
                .collect(Collectors.toMap(ContentTag::getName, t -> t, (a, b) -> a));
        Set<String> currentTagNames = currentTagMap.keySet();

        // 步骤 3：计算集合差集，得出新增标签 (toAdd) 与被移除标签 (toRemove)
        Set<String> toAdd = new HashSet<>(newTagNames);
        toAdd.removeAll(currentTagNames);

        Set<String> toRemove = new HashSet<>(currentTagNames);
        toRemove.removeAll(newTagNames);

        // 步骤 4：处理新增标签：若字典中不存在则原子创建，批量插入关联记录，并将热度计数 +1
        if (!toAdd.isEmpty()) {
            List<VideoTagRel> relsToInsert = new ArrayList<>();
            for (String tagName : toAdd) {
                ContentTag tag = contentTagRepository.findOrCreate(tagName);
                relsToInsert.add(VideoTagRel.create(null, videoId, tag.getId()));
                contentTagRepository.updateReferenceCount(tag.getId(), 1L);
            }
            videoTagRelRepository.batchInsert(relsToInsert);
        }

        // 步骤 5：处理被移除的标签：递减对应热度引用计数 (-1)
        if (!toRemove.isEmpty()) {
            for (String tagName : toRemove) {
                ContentTag tag = currentTagMap.get(tagName);
                if (tag != null) {
                    contentTagRepository.updateReferenceCount(tag.getId(), -1L);
                }
            }
            // 步骤 6：刷新或清理关联关系表记录
            if (newTagNames.isEmpty()) {
                videoTagRelRepository.deleteByVideoId(videoId);
            } else {
                videoTagRelRepository.deleteByVideoId(videoId);
                List<VideoTagRel> remainingRels = new ArrayList<>();
                for (String tagName : newTagNames) {
                    ContentTag tag = contentTagRepository.findOrCreate(tagName);
                    remainingRels.add(VideoTagRel.create(null, videoId, tag.getId()));
                }
                videoTagRelRepository.batchInsert(remainingRels);
            }
        }
    }

    /**
     * 根据视频内部 ID 查询其关联绑定的全部标签名称列表。
     *
     * @param videoId 视频内部全局主键 ID
     * @return 标签文本名称列表（按关联绑定时间正序）
     */
    public List<String> getTagNamesByVideoId(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return Collections.emptyList();
        }
        // 步骤 1：查询视频绑定的标签 ID 列表
        List<String> tagIds = videoTagRelRepository.findTagIdsByVideoId(videoId);
        if (tagIds.isEmpty()) {
            return Collections.emptyList();
        }
        // 步骤 2：批量加载标签实体并提取其唯一名称
        return contentTagRepository.findByIds(tagIds).stream()
                .map(ContentTag::getName)
                .toList();
    }

    /**
     * 获取全站热门标签列表。
     *
     * @param limit 期望获取数量（内部自动限制在 1 到 50 之间）
     * @return 按引用热度排序的热门标签领域实体列表
     */
    public List<ContentTag> getHotTags(int limit) {
        // 步骤 1：施加最大 50 条的上限保护
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        // 步骤 2：按引用热度计数倒序检索字典
        return contentTagRepository.findTopHotTags(boundedLimit);
    }

    /**
     * 解析原始标签字符串，规范化分隔符并过滤无效空白项。
     *
     * @param rawTags 原始输入的标签文本
     * @return 清洗后的规范化非空标签名集合
     */
    private Set<String> parseTagNames(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return Collections.emptySet();
        }
        // 步骤 1：兼容中英文逗号，拆分后 trim 并过滤空白
        return Arrays.stream(rawTags.replace("，", ",").split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }
}
