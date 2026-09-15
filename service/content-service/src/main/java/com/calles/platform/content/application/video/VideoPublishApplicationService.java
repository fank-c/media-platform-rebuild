package com.calles.platform.content.application.video;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.content.application.client.FileMetadataDTO;
import com.calles.platform.content.application.client.FileServiceClient;
import com.calles.platform.content.application.security.ContentAccessPolicy;
import com.calles.platform.content.application.tag.ContentTagApplicationService;
import com.calles.platform.content.application.util.Base62VidGenerator;
import com.calles.platform.content.domain.model.video.ContentVisibility;
import com.calles.platform.content.domain.model.video.PublishStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.exception.ContentException;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxMapper;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxRecord;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频创作发布应用服务。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：负责创作者工作流生命周期管理，涵盖草稿新建、图文元数据修订、提审文件资产校验、主动下架及逻辑删除；</li>
 *   <li><b>协作对象</b>：
 *     <ul>
 *       <li>{@link VideoContentRepository}：视频聚合根持久化与乐观锁更新；</li>
 *       <li>{@link ContentTagApplicationService}：同步标签文本并维护全局字典热度；</li>
 *       <li>{@link FileServiceClient}：提审前远程调用文件微服务，校验音视频源文件与封面图就绪状态；</li>
 *       <li>{@link ContentAccessPolicy}：校验创作者所有权或平台治理权限；</li>
 *       <li>{@link ContentOutboxMapper}：在同一本地事务中记录提审/下架领域事件。</li>
 *     </ul>
 *   </li>
 *   <li><b>一致性保障</b>：运用事务性发件箱 (Transactional Outbox) 模式，保障状态变更与异步事件发布的强一致。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoPublishApplicationService {

    /** 视频聚合根仓储。 */
    private final VideoContentRepository videoContentRepository;

    /** 标签应用服务。 */
    private final ContentTagApplicationService contentTagApplicationService;

    /** 文件微服务远程声明式客户端。 */
    private final FileServiceClient fileServiceClient;

    /** 访问控制与鉴权策略。 */
    private final ContentAccessPolicy accessPolicy;

    /** 事务性发件箱 Mapper。 */
    private final ContentOutboxMapper contentOutboxMapper;

    /** 视频异步流水线任务协调器。 */
    private final com.calles.platform.content.application.task.VideoTaskCoordinator videoTaskCoordinator;

    /**
     * 创建视频草稿。
     *
     * @param authorId 当前登录创作者账号 ID
     * @param request 创建草稿参数传输对象
     * @return 系统生成的 24 位唯一业务公开编码 vid (如 cv05hG9Kq2RtLw7XbPmZv4Ya)
     */
    @Transactional
    public String createDraft(String authorId, VideoRequests.CreateDraft request) {
        // 步骤 1：生成内部 UUID 与高熵无序的 Base62 业务短码 vid
        String id = UUID.randomUUID().toString().replace("-", "");
        String vid = Base62VidGenerator.generateVid();

        // 步骤 2：通过聚合根工厂方法初始化草稿状态实体 (DRAFT)
        VideoContent video = VideoContent.createDraft(
                id,
                vid,
                authorId,
                request.title(),
                request.description(),
                request.videoFileId(),
                request.coverFileId(),
                request.duration(),
                request.tags()
        );

        // 步骤 3：解析并设置公开可见性，默认回退为 PUBLIC
        if (request.visibility() != null && !request.visibility().isBlank()) {
            try {
                video.setVisibility(ContentVisibility.valueOf(request.visibility().trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                video.setVisibility(ContentVisibility.PUBLIC);
            }
        }

        // 步骤 4：持久化草稿聚合根并异步/同步建立标签字典关联
        videoContentRepository.insert(video);
        contentTagApplicationService.syncVideoTags(id, request.tags());

        log.info("创作者 [{}] 成功创建视频草稿，id=[{}], vid=[{}]", authorId, id, vid);
        return vid;
    }

    /**
     * 修改视频元数据（仅允许草稿或被驳回状态修改）。
     *
     * @param authorId 当前登录账号 ID
     * @param id 视频内部主键 ID
     * @param request 元数据更新内容请求体
     * @throws ContentException 当非草稿/驳回状态修改，或发生并发修改冲突时抛出
     */
    @Transactional
    public void updateMetadata(String authorId, String id, VideoRequests.UpdateMetadata request) {
        // 步骤 1：定位目标视频并校验创作者本人操作权限
        VideoContent video = findVideoOrThrow(id);
        accessPolicy.requireOwnerOrAdmin(video.getAuthorId());

        // 步骤 2：状态机约束校验：已发布或审核中的内容禁止直接修改元数据
        if (video.getPublishStatus() != PublishStatus.DRAFT && video.getPublishStatus() != PublishStatus.REJECTED) {
            throw new ContentException(HttpStatus.BAD_REQUEST, "只有草稿或被驳回状态的视频才允许修改元数据");
        }

        // 步骤 3：修改领域实体属性并执行乐观锁版本更新
        video.updateMetadata(request.title(), request.description(), request.coverFileId(), request.tags());
        int updated = videoContentRepository.updateById(video);
        if (updated == 0) {
            throw new ContentException(HttpStatus.CONFLICT, "视频信息已被并发修改，请刷新后重试");
        }

        // 步骤 4：同步全量更新轻量标签字典与热度
        if (request.tags() != null) {
            contentTagApplicationService.syncVideoTags(id, request.tags());
        }
        log.info("创作者 [{}] 更新了视频 [{}] 元数据", authorId, id);
    }

    /**
     * 提交视频进入平台审核流水线。
     *
     * @param authorId 创作者账号 ID
     * @param id 视频内部全局主键 ID
     * @throws ContentException 当文件未就绪、状态非法或并发冲突时抛出
     */
    @Transactional
    public void submitForAudit(String authorId, String id) {
        // 步骤 1：定位视频实体并进行创作者身份鉴权
        VideoContent video = findVideoOrThrow(id);
        accessPolicy.requireOwnerOrAdmin(video.getAuthorId());

        // 步骤 2：通过 Feign 同步校验主视频文件与封面文件在 file-service 中是否已就绪 (CONFIRMED & ACTIVE)
        verifyFileAssetReady(video.getVideoFileId(), authorId, "主视频文件");
        verifyFileAssetReady(video.getCoverFileId(), authorId, "封面图片文件");

        // 步骤 3：聚合根内部状态跃迁至 AUDITING 审核中状态
        try {
            video.submitForAudit();
        } catch (IllegalStateException e) {
            throw new ContentException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        int updated = videoContentRepository.updateById(video);
        if (updated == 0) {
            throw new ContentException(HttpStatus.CONFLICT, "状态更新冲突，请刷新重试");
        }

        // 步骤 4：在本地事务中持久化 Outbox 记录，发布 content.video.submitted 供审核微服务消费
        Instant now = Instant.now();
        ContentOutboxRecord outboxRecord = ContentOutboxRecord.of(
                video.getId(),
                "content.video.submitted",
                String.format("{\"videoId\":\"%s\",\"vid\":\"%s\",\"authorId\":\"%s\",\"videoFileId\":\"%s\",\"coverFileId\":\"%s\"}",
                        video.getId(), video.getVid(), video.getAuthorId(), video.getVideoFileId(), video.getCoverFileId()),
                now
        );
        contentOutboxMapper.insert(outboxRecord, Timestamp.from(now), "PENDING", Timestamp.from(now));

        // 步骤 5：初始化生成 5 个流水线子任务（审核、各规格转码、向量提取），进入就绪门禁管理
        videoTaskCoordinator.initPipelineTasks(video.getId());

        log.info("视频 [{}] 已成功提交审核，已初始化流水线任务并记录 Outbox 待发布事件", id);
    }


    /**
     * 创作者主动下架已发布的视频。
     *
     * @param authorId 创作者账号 ID
     * @param id 视频内部全局主键 ID
     * @param reason 下架原因说明
     * @throws ContentException 当非已发布状态或并发冲突时抛出
     */
    @Transactional
    public void takeOffline(String authorId, String id, String reason) {
        // 步骤 1：定位视频并校验创作者本人权限
        VideoContent video = findVideoOrThrow(id);
        accessPolicy.requireOwnerOrAdmin(video.getAuthorId());

        // 步骤 2：状态机流转为 OFFLINE
        try {
            video.takeOffline(reason);
        } catch (IllegalStateException e) {
            throw new ContentException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        int updated = videoContentRepository.updateById(video);
        if (updated == 0) {
            throw new ContentException(HttpStatus.CONFLICT, "状态更新冲突，请重试");
        }

        // 步骤 3：写入 content.video.offline 发件箱事件，通知搜索/推荐系统即时下线
        Instant now = Instant.now();
        ContentOutboxRecord outboxRecord = ContentOutboxRecord.of(
                video.getId(),
                "content.video.offline",
                String.format("{\"videoId\":\"%s\",\"vid\":\"%s\",\"reason\":\"%s\"}",
                        video.getId(), video.getVid(), reason != null ? reason : ""),
                now
        );
        contentOutboxMapper.insert(outboxRecord, Timestamp.from(now), "PENDING", Timestamp.from(now));

        log.info("创作者 [{}] 主动下架了视频 [{}]", authorId, id);
    }

    /**
     * 逻辑删除视频。
     *
     * @param authorId 创作者账号 ID
     * @param id 视频内部全局主键 ID
     */
    @Transactional
    public void deleteVideo(String authorId, String id) {
        // 步骤 1：校验归属权
        VideoContent video = findVideoOrThrow(id);
        accessPolicy.requireOwnerOrAdmin(video.getAuthorId());

        // 步骤 2：标记逻辑删除
        videoContentRepository.deleteById(id);
        // 步骤 3：清理该视频绑定的所有标签热度计数
        contentTagApplicationService.syncVideoTags(id, "");

        log.info("创作者 [{}] 删除了视频 [{}]", authorId, id);
    }

    /**
     * 根据主键 ID 检索有效视频，未命中则统一抛出 404 业务异常。
     *
     * @param id 视频主键 ID
     * @return 查找到的视频领域聚合根
     * @throws ContentException 当记录不存在时抛出 404 NOT_FOUND
     */
    private VideoContent findVideoOrThrow(String id) {
        return videoContentRepository.findById(id)
                .orElseThrow(() -> new ContentException(HttpStatus.NOT_FOUND, "未找到指定视频内容"));
    }

    /**
     * 远程校验依赖的文件资产是否已正常就绪。
     *
     * @param fileId 文件全局资产 ID
     * @param userId 创作者账号 ID
     * @param fileDesc 文件业务描述（如“主视频文件”或“封面图片文件”）
     * @throws ContentException 当文件校验失败或未处于 CONFIRMED 状态时抛出 400 BAD_REQUEST
     */
    private void verifyFileAssetReady(String fileId, String userId, String fileDesc) {
        try {
            // 步骤 1：向 file-service 发起 RPC 查询文件元数据
            ApiResponse<FileMetadataDTO> response = fileServiceClient.getFileMetadata(fileId, userId, "USER");
            // 步骤 2：核验响应体及 isReady 状态
            if (response == null || response.data() == null || !response.data().isReady()) {
                throw new ContentException(HttpStatus.BAD_REQUEST, fileDesc + "尚未上传就绪或已失效，请重新确认上传");
            }
        } catch (ContentException e) {
            throw e;
        } catch (Exception e) {
            // 步骤 3：捕获下游超时或熔断异常，转换为对前端友好的提示
            log.warn("调用 file-service 校验文件 [{}] 发生异常: {}", fileId, e.getMessage());
            throw new ContentException(HttpStatus.BAD_REQUEST, fileDesc + "状态校验失败: " + e.getMessage());
        }
    }
}
