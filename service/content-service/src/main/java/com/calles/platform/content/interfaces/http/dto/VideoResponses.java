package com.calles.platform.content.interfaces.http.dto;

import com.calles.platform.content.interfaces.http.tag.TagController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 视频与内容相关的 HTTP 响应传输对象命名空间集合。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：接口层出参契约定义，隔离领域聚合实体（防止实体泄露或循环引用）；</li>
 *   <li><b>协作对象</b>：供 {@link com.calles.platform.content.interfaces.http.VideoController} 与 {@link TagController} 返回给网关及前端消费；</li>
 *   <li><b>数据格式</b>：对齐 ISO 8601 时间规范与驼峰命名标准，保障各端统一解析。</li>
 * </ul>
 * </p>
 */
public final class VideoResponses {

    /**
     * 内部私有构造器，避免作为工具命名空间被外部意外实例化。
     */
    private VideoResponses() {
    }

    /**
     * 前台视频完整详情出参对象。
     *
     * @param id 视频内部主键 ID (UUID 32位无短横线)
     * @param vid 业务公开编码 (如 cv05hG9Kq2RtLw7XbPmZv4Ya)
     * @param authorId 创作者用户账号 ID
     * @param title 视频标题
     * @param description 视频详细图文介绍
     * @param coverFileId 封面图片在 file-service 中的文件资产 ID
     * @param videoFileId 主视频原始文件在 file-service 中的文件资产 ID
     * @param duration 视频时长 (秒)
     * @param tags 关联的标签名称数组 (如 ["Java", "微服务"])
     * @param status 平台治理状态 (ACTIVE=正常, DISABLED=违规封禁)
     * @param publishStatus 发布流转生命周期 (PUBLISHED 等)
     * @param visibility 可见范围 (PUBLIC, PRIVATE, UNLISTED)
     * @param viewCount 播放量快照
     * @param likeCount 点赞量快照
     * @param commentCount 评论量快照
     * @param starCount 收藏量快照
     * @param shareCount 分享量快照
     * @param publishedAt 正式公开时间
     * @param createdAt 记录创建时间
     */
    public record Detail(
            String id,
            String vid,
            String authorId,
            String title,
            String description,
            String coverFileId,
            String videoFileId,
            int duration,
            List<String> tags,
            String status,
            String publishStatus,
            String visibility,
            long viewCount,
            long likeCount,
            long commentCount,
            long starCount,
            long shareCount,
            LocalDateTime publishedAt,
            LocalDateTime createdAt
    ) { }

    /**
     * 单条可用转码播放流出参信息。
     *
     * @param quality 清晰度画质规格 (如 360P, 720P, 1080P, 4K, RAW)
     * @param format 流媒体封装格式 (MP4, HLS, DASH)
     * @param codec 视频编码格式 (H264, H265, AV1)
     * @param fileId 流文件在 file-service 中的文件资产 ID
     * @param fileSize 流文件物理字节数
     * @param bitrate 视频码率 (kbps)
     * @param fps 视频帧率 (fps)
     */
    public record PlayStream(
            String quality,
            String format,
            String codec,
            String fileId,
            long fileSize,
            Integer bitrate,
            Integer fps
    ) { }

    /**
     * 视频多清晰度播放流列表出参响应。
     *
     * @param vid 业务公开编码
     * @param title 视频标题
     * @param streams 所有转码完成且可播放的流规格列表
     */
    public record PlayStreams(
            String vid,
            String title,
            List<PlayStream> streams
    ) { }

    /**
     * 创作者工作台作品列表项。
     *
     * @param id 视频内部主键 ID
     * @param vid 业务公开编码
     * @param title 视频标题
     * @param coverFileId 封面文件资产 ID
     * @param duration 视频时长 (秒)
     * @param status 平台可用状态 (ACTIVE, DISABLED)
     * @param publishStatus 创作生命周期 (DRAFT, AUDITING, PUBLISHED, REJECTED, OFFLINE)
     * @param rejectReason 驳回或下架的具体原因说明
     * @param publishedAt 正式公开发布时间 (未发布时为空)
     * @param createdAt 创建时间
     * @param updatedAt 最后修改时间
     */
    public record CreatorItem(
            String id,
            String vid,
            String title,
            String coverFileId,
            int duration,
            String status,
            String publishStatus,
            String rejectReason,
            LocalDateTime publishedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) { }

    /**
     * 创作者工作台分页列表响应。
     *
     * @param records 当前页的视频项列表
     * @param total 符合条件的全部记录总数
     * @param page 当前页码 (从 1 起始)
     * @param size 当前每页大小
     */
    public record CreatorPage(
            List<CreatorItem> records,
            long total,
            long page,
            long size
    ) { }

    /**
     * 平台管理端视频检索列表项。
     *
     * @param id 视频内部全局主键 ID
     * @param vid 业务公开编码
     * @param authorId 创作者账号 ID
     * @param title 视频标题
     * @param coverFileId 封面图片文件资产 ID
     * @param status 平台治理状态 (ACTIVE, DISABLED)
     * @param publishStatus 发布流转生命周期 (DRAFT, AUDITING, PUBLISHED, REJECTED, OFFLINE)
     * @param rejectReason 违规封禁或审核驳回的具体原因
     * @param publishedAt 公开发布时间
     * @param createdAt 创建时间
     */
    public record AdminItem(
            String id,
            String vid,
            String authorId,
            String title,
            String coverFileId,
            String status,
            String publishStatus,
            String rejectReason,
            LocalDateTime publishedAt,
            LocalDateTime createdAt
    ) { }

    /**
     * 平台管理端分页列表响应。
     *
     * @param records 当前页的管理记录项列表
     * @param total 检索过滤后总记录数
     * @param page 当前页码
     * @param size 每页大小
     */
    public record AdminPage(
            List<AdminItem> records,
            long total,
            long page,
            long size
    ) { }

    /**
     * 全站热门轻量标签项。
     *
     * @param id 标签主键 ID
     * @param name 标签文本名称 (如 "Java")
     * @param tagType 标签类型编码 (DOMAIN=泛化领域, TOPIC=具体主题)
     * @param referenceCount 关联的已发布视频热度引用计数
     */
    public record HotTag(
            String id,
            String name,
            String tagType,
            long referenceCount
    ) {
        /**
         * 兼容历史调用的重载构造方法，默认类型为 TOPIC。
         *
         * @param id 标签主键 ID
         * @param name 标签名称
         * @param referenceCount 引用计数
         */
        public HotTag(String id, String name, long referenceCount) {
            this(id, name, "TOPIC", referenceCount);
        }
    }

    /**
     * 流水线单项子任务执行进度明细。
     *
     * @param id 任务全局唯一主键 ID
     * @param taskType 任务类型编码 (AUDIT, TRANSCODE_720P, TRANSCODE_1080P, TRANSCODE_4K, VECTOR_EMBEDDING)
     * @param taskName 任务展示名称
     * @param status 任务状态 (PENDING, RUNNING, SUCCESS, FAILED, CANCELED)
     * @param progress 执行进度百分比 (0-100)
     * @param retryCount 已重试次数
     * @param errorMessage 失败错误说明
     * @param startedAt 开始执行时间
     * @param completedAt 完成时间
     */
    public record TaskProgressItem(
            String id,
            String taskType,
            String taskName,
            String status,
            int progress,
            int retryCount,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) { }

    /**
     * 视频发布流水线全景进度状态响应。
     *
     * @param videoId 视频全局唯一 ID
     * @param publishStatus 视频当前发布生命周期 (AUDITING, PUBLISHED 等)
     * @param eligibleForPublish 是否已满足分级就绪门禁
     * @param tasks 所有子任务明细列表
     */
    public record PipelineProgress(
            String videoId,
            String publishStatus,
            boolean eligibleForPublish,
            List<TaskProgressItem> tasks
    ) { }
}

