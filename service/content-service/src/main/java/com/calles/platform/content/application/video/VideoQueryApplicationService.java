package com.calles.platform.content.application.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.calles.platform.content.application.tag.ContentTagApplicationService;
import com.calles.platform.content.domain.model.CommonStatus;
import com.calles.platform.content.domain.model.stream.TranscodeStatus;
import com.calles.platform.content.domain.model.stream.VideoStream;
import com.calles.platform.content.domain.model.video.ContentVisibility;
import com.calles.platform.content.domain.model.video.PublishStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.domain.repository.VideoStreamRepository;
import com.calles.platform.content.exception.ContentException;
import com.calles.platform.content.infrastructure.persistence.entity.VideoContentPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoContentMapper;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 视频查询读模型应用服务。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：负责视频内容 CQRS 读模型构建，包括前台图文详情读取、多清晰度播放流切片汇聚、创作者作品列表与管理端后台检索；</li>
 *   <li><b>协作对象</b>：
 *     <ul>
 *       <li>{@link VideoContentRepository}：聚合根领域只读检索；</li>
 *       <li>{@link VideoStreamRepository}：转码播放流检索；</li>
 *       <li>{@link ContentTagApplicationService}：视频绑定标签轻量反查；</li>
 *       <li>{@link VideoContentMapper}：支持复杂多条件动态筛选与高效只读分页。</li>
 *     </ul>
 *   </li>
 *   <li><b>安全防线</b>：严格执行多维度访问权限门禁（未发布、违规封禁或私密视频仅作者本人与管理员可见）。</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class VideoQueryApplicationService {

    /** 视频聚合根持久化仓储。 */
    private final VideoContentRepository videoContentRepository;

    /** 视频转码流仓储。 */
    private final VideoStreamRepository videoStreamRepository;

    /** 标签应用服务。 */
    private final ContentTagApplicationService contentTagApplicationService;

    /** 视频 MyBatis-Plus 数据访问 Mapper。 */
    private final VideoContentMapper videoContentMapper;

    /**
     * 前台根据业务公开短码 vid 查询视频公开图文详情。
     *
     * @param vid 24 位高熵业务编码
     * @param currentUserId 当前访问用户 ID (可为空，支持匿名访问)
     * @param isAdmin 当前访问主体是否具备平台管理员权限
     * @return 组装完成的视频完整详情传输对象
     * @throws ContentException 当视频不存在或无权访问时抛出 404 NOT_FOUND
     */
    public VideoResponses.Detail getVideoDetail(String vid, String currentUserId, boolean isAdmin) {
        // 步骤 1：按业务短码检索未逻辑删除的视频聚合根
        VideoContent video = videoContentRepository.findByVid(vid)
                .orElseThrow(() -> new ContentException(HttpStatus.NOT_FOUND, "未找到指定视频内容"));

        // 步骤 2：执行三层状态与可见性门禁校验（封禁、发布生命周期、私密性）
        checkViewPermission(video, currentUserId, isAdmin);

        // 步骤 3：查询该视频绑定的所有标签名称列表
        List<String> tags = contentTagApplicationService.getTagNamesByVideoId(video.getId());

        // 步骤 4：转换为只读 DTO 并返回
        return new VideoResponses.Detail(
                video.getId(),
                video.getVid(),
                video.getAuthorId(),
                video.getTitle(),
                video.getDescription(),
                video.getCoverFileId(),
                video.getVideoFileId(),
                video.getDuration(),
                tags,
                video.getStatus().getValue(),
                video.getPublishStatus().getValue(),
                video.getVisibility().getValue(),
                video.getPublishedAt(),
                video.getCreatedAt()
        );
    }

    /**
     * 获取视频可供播放的可用流媒体切片列表。
     *
     * @param vid 24 位业务编码
     * @param currentUserId 当前访问人 ID (可为空)
     * @param isAdmin 是否具备平台管理员角色
     * @return 包含所有 COMPLETED 已转码就绪清晰度的播放流列表响应
     * @throws ContentException 当视频不存在或无权访问时抛出 404 NOT_FOUND
     */
    public VideoResponses.PlayStreams getPlayStreams(String vid, String currentUserId, boolean isAdmin) {
        // 步骤 1：检索视频聚合根并执行观看权限门禁校验
        VideoContent video = videoContentRepository.findByVid(vid)
                .orElseThrow(() -> new ContentException(HttpStatus.NOT_FOUND, "未找到指定视频内容"));

        checkViewPermission(video, currentUserId, isAdmin);

        // 步骤 2：查询该视频名下的所有转码流，并仅保留处于 COMPLETED 成功就绪状态的切片
        List<VideoStream> allStreams = videoStreamRepository.findByVideoId(video.getId());
        List<VideoResponses.PlayStream> playStreams = allStreams.stream()
                .filter(s -> s.getTranscodeStatus() == TranscodeStatus.COMPLETED)
                .map(s -> new VideoResponses.PlayStream(
                        s.getQuality().getValue(),
                        s.getFormat().getValue(),
                        s.getCodec().getValue(),
                        s.getFileId(),
                        s.getFileSize(),
                        s.getBitrate(),
                        s.getFps()
                ))
                .toList();

        // 步骤 3：组装并返回多码率流清单
        return new VideoResponses.PlayStreams(video.getVid(), video.getTitle(), playStreams);
    }

    /**
     * 创作者工作台作品列表分页查询。
     *
     * @param authorId 创作者账号 ID
     * @param publishStatus 发布流转状态过滤条件 (可选，如 DRAFT, AUDITING 等)
     * @param page 请求页码 (从 1 起始)
     * @param size 每页记录大小 (最大 100)
     * @return 创作者工作台专属分页响应
     */
    public VideoResponses.CreatorPage listMyVideos(String authorId, String publishStatus, int page, int size) {
        // 步骤 1：实施分页参数保护与边界约束
        int boundedPage = Math.max(1, page);
        int boundedSize = Math.max(1, Math.min(size, 100));

        // 步骤 2：构建创作者名下未删除视频的查询条件
        LambdaQueryWrapper<VideoContentPO> wrapper = new LambdaQueryWrapper<VideoContentPO>()
                .eq(VideoContentPO::getAuthorId, authorId)
                .eq(VideoContentPO::getDeleted, 0);

        if (publishStatus != null && !publishStatus.isBlank()) {
            wrapper.eq(VideoContentPO::getPublishStatus, publishStatus.trim().toUpperCase());
        }
        wrapper.orderByDesc(VideoContentPO::getCreatedAt);

        // 步骤 3：执行物理分页查询并映射为 CreatorItem 列表
        Page<VideoContentPO> queryPage = videoContentMapper.selectPage(new Page<>(boundedPage, boundedSize), wrapper);

        List<VideoResponses.CreatorItem> items = queryPage.getRecords().stream()
                .map(po -> new VideoResponses.CreatorItem(
                        po.getId(),
                        po.getVid(),
                        po.getTitle(),
                        po.getCoverFileId(),
                        po.getDuration() != null ? po.getDuration() : 0,
                        po.getStatus(),
                        po.getPublishStatus(),
                        po.getRejectReason(),
                        po.getPublishedAt(),
                        po.getCreatedAt(),
                        po.getUpdatedAt()
                ))
                .toList();

        return new VideoResponses.CreatorPage(items, queryPage.getTotal(), boundedPage, boundedSize);
    }

    /**
     * 平台管理端综合多条件分页检索全站视频。
     *
     * @param request 组合筛选过滤请求体 (可包含状态、作者、模糊搜索关键词)
     * @param page 请求页码
     * @param size 每页大小
     * @return 管理端专属分页响应
     */
    public VideoResponses.AdminPage listAdminVideos(VideoRequests.AdminList request, int page, int size) {
        // 步骤 1：分页参数上下限防护
        int boundedPage = Math.max(1, page);
        int boundedSize = Math.max(1, Math.min(size, 100));

        // 步骤 2：动态装配管理端复合过滤条件
        LambdaQueryWrapper<VideoContentPO> wrapper = new LambdaQueryWrapper<VideoContentPO>()
                .eq(VideoContentPO::getDeleted, 0);

        if (request != null) {
            if (request.status() != null && !request.status().isBlank()) {
                wrapper.eq(VideoContentPO::getStatus, request.status().trim().toUpperCase());
            }
            if (request.publishStatus() != null && !request.publishStatus().isBlank()) {
                wrapper.eq(VideoContentPO::getPublishStatus, request.publishStatus().trim().toUpperCase());
            }
            if (request.authorId() != null && !request.authorId().isBlank()) {
                wrapper.eq(VideoContentPO::getAuthorId, request.authorId().trim());
            }
            if (request.keyword() != null && !request.keyword().isBlank()) {
                wrapper.and(w -> w.like(VideoContentPO::getTitle, request.keyword().trim())
                        .or().like(VideoContentPO::getVid, request.keyword().trim()));
            }
        }
        wrapper.orderByDesc(VideoContentPO::getCreatedAt);

        // 步骤 3：执行持久化分页检索并映射为 AdminItem
        Page<VideoContentPO> queryPage = videoContentMapper.selectPage(new Page<>(boundedPage, boundedSize), wrapper);

        List<VideoResponses.AdminItem> items = queryPage.getRecords().stream()
                .map(po -> new VideoResponses.AdminItem(
                        po.getId(),
                        po.getVid(),
                        po.getAuthorId(),
                        po.getTitle(),
                        po.getCoverFileId(),
                        po.getStatus(),
                        po.getPublishStatus(),
                        po.getRejectReason(),
                        po.getPublishedAt(),
                        po.getCreatedAt()
                ))
                .toList();

        return new VideoResponses.AdminPage(items, queryPage.getTotal(), boundedPage, boundedSize);
    }

    /**
     * 校验前台访问人对目标视频的浏览查看权限门禁。
     *
     * @param video 视频聚合根实体
     * @param currentUserId 当前访问用户 ID（可为空）
     * @param isAdmin 是否具备平台管理员权限
     * @throws ContentException 当不满足可见性条件时抛出 404 NOT_FOUND（对外统一脱敏为不存在）
     */
    private void checkViewPermission(VideoContent video, String currentUserId, boolean isAdmin) {
        boolean isOwner = currentUserId != null && currentUserId.equals(video.getAuthorId());

        // 门禁 1：被平台治理封禁 (DISABLED) 的视频，仅作者本人与管理员可见
        if (video.getStatus() == CommonStatus.DISABLED && !isOwner && !isAdmin) {
            throw new ContentException(HttpStatus.NOT_FOUND, "视频已被平台封禁或不可用");
        }

        // 门禁 2：未正式公开发布的视频 (草稿、审核中、审核驳回、下架) 仅作者本人与管理员可见
        if (video.getPublishStatus() != PublishStatus.PUBLISHED && !isOwner && !isAdmin) {
            throw new ContentException(HttpStatus.NOT_FOUND, "未找到指定视频内容");
        }

        // 门禁 3：创作者标记为私密 (PRIVATE) 的视频仅作者本人与管理员可见
        if (video.getVisibility() == ContentVisibility.PRIVATE && !isOwner && !isAdmin) {
            throw new ContentException(HttpStatus.NOT_FOUND, "该视频为创作者私密内容");
        }
    }

    /**
     * 根据主键查询未删除的视频聚合根，不存在时抛出 404 异常。
     *
     * @param id 视频全局唯一主键 ID
     * @return 视频聚合根实体
     * @throws ContentException 404 NOT_FOUND
     */
    public VideoContent findVideoOrThrow(String id) {
        return videoContentRepository.findById(id)
                .filter(v -> !v.isDeleted())
                .orElseThrow(() -> new ContentException(HttpStatus.NOT_FOUND, "未找到指定的视频内容: " + id));
    }
}

