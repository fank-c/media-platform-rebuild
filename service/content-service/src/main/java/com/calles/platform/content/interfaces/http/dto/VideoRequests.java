package com.calles.platform.content.interfaces.http.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 视频与内容相关的 HTTP 请求参数传输对象命名空间集合。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：接口层入参契约定义，统一聚合创作者端、管理端与微服务内部回调的数据结构；</li>
 *   <li><b>校验规则</b>：通过 Jakarta Validation (JSR-303) 注解强制进行字段长度、非空与数值边界校验；</li>
 *   <li><b>协作对象</b>：供 {@link com.calles.platform.content.interfaces.http.VideoController} 接收客户端与外部服务请求。</li>
 * </ul>
 * </p>
 */
public final class VideoRequests {

    /**
     * 内部私有构造器，避免作为工具命名空间被外部意外实例化。
     */
    private VideoRequests() {
    }

    /**
     * 创建视频草稿请求体。
     *
     * @param title 视频展示标题（必填，最大 128 字符）
     * @param description 视频详细文本简介（选填，最大 2000 字符）
     * @param videoFileId 已在 file-service 预上传就绪的主视频文件资产 ID（必填，32位字符）
     * @param coverFileId 已在 file-service 预上传就绪的封面图片资产 ID（必填，32位字符）
     * @param duration 视频时长（必填，单位：秒，不可为负数）
     * @param tags 逗号分隔的轻量标签文本（选填，如 "Java,微服务"，最大 255 字符）
     * @param visibility 可见性范围（选填，PUBLIC=公开, PRIVATE=私密, UNLISTED=链接可见；默认为 PUBLIC）
     */
    public record CreateDraft(
            @NotBlank(message = "视频标题不能为空")
            @Size(max = 128, message = "视频标题不能超过 128 字符")
            String title,

            @Size(max = 2000, message = "视频简介不能超过 2000 字符")
            String description,

            @NotBlank(message = "主视频文件资产 ID 不能为空")
            String videoFileId,

            @NotBlank(message = "封面文件资产 ID 不能为空")
            String coverFileId,

            @NotNull(message = "视频时长不能为空")
            @Min(value = 0, message = "视频时长不能为负数")
            Integer duration,

            @Size(max = 255, message = "标签总长度不能超过 255 字符")
            String tags,

            String visibility
    ) { }

    /**
     * 修改视频元数据请求体。
     *
     * @param title 更新后的视频标题（选填，若提供则不可超过 128 字符）
     * @param description 更新后的视频简介（选填，若提供则不可超过 2000 字符）
     * @param coverFileId 更新后的封面图片资产 ID（选填，若提供须指向有效文件资产）
     * @param tags 更新后的逗号分隔轻量标签文本（选填，全量覆盖旧标签并重新计算热度）
     */
    public record UpdateMetadata(
            @Size(max = 128, message = "视频标题不能超过 128 字符")
            String title,

            @Size(max = 2000, message = "视频简介不能超过 2000 字符")
            String description,

            String coverFileId,

            @Size(max = 255, message = "标签总长度不能超过 255 字符")
            String tags
    ) { }

    /**
     * 创作者主动下架视频请求体。
     *
     * @param reason 下架原因或说明（选填，最大 255 字符）
     */
    public record Offline(
            @Size(max = 255, message = "下架原因不能超过 255 字符")
            String reason
    ) { }

    /**
     * 管理后台违规封禁视频请求体。
     *
     * @param reason 封禁的具体原因说明（必填，最大 255 字符，供审计与向创作者展示）
     */
    public record AdminBan(
            @NotBlank(message = "封禁原因不能为空")
            @Size(max = 255, message = "封禁原因不能超过 255 字符")
            String reason
    ) { }

    /**
     * 管理后台视频列表检索过滤条件请求体。
     *
     * @param status 平台可用状态过滤（选填，ACTIVE 或 DISABLED）
     * @param publishStatus 发布流转状态过滤（选填，DRAFT, AUDITING, PUBLISHED, REJECTED, OFFLINE）
     * @param authorId 创作者用户 ID 筛选（选填）
     * @param keyword 模糊搜索关键字（选填，匹配标题 title 或公开编码 vid）
     */
    public record AdminList(
            String status,
            String publishStatus,
            String authorId,
            String keyword
    ) { }

    /**
     * 审核微服务异步判定结果回调请求体。
     *
     * @param videoId 关联的视频内部全局主键 ID（必填，32位字符）
     * @param passed 审核是否通过判定（必填，true=通过并自动发布，false=打回草稿箱）
     * @param rejectReason 审核驳回原因（当 passed 为 false 时必选或由审核系统生成，记录违规项）
     */
    public record AuditCallback(
            @NotBlank(message = "视频 ID 不能为空")
            String videoId,

            @NotNull(message = "审核结果状态不能为空")
            Boolean passed,

            String rejectReason
    ) { }

    /**
     * 媒体转码微服务切片产物注册回调请求体。
     *
     * @param videoId 关联的视频内部主键 ID（必填）
     * @param quality 转码清晰度规格（必填，如 360P, 720P, 1080P, 4K, RAW）
     * @param format 流媒体封装格式（选填，MP4, HLS, DASH；默认为 MP4）
     * @param codec 视频压缩编码标准（选填，H264, H265, AV1；默认为 H264）
     * @param fileId 转码生成的流媒体切片在 file-service 中的文件资产 ID（必填）
     * @param fileSize 转码产物文件大小（选填，单位：字节）
     * @param bitrate 码率（选填，单位：kbps）
     * @param fps 视频帧率（选填，单位：fps）
     * @param transcodeStatus 转码状态（选填，PENDING, PROCESSING, COMPLETED, FAILED；默认为 COMPLETED）
     */
    public record TranscodeCallback(
            @NotBlank(message = "视频 ID 不能为空")
            String videoId,

            @NotBlank(message = "清晰度画质规格不能为空")
            String quality,

            String format,

            String codec,

            @NotBlank(message = "转码文件 ID 不能为空")
            String fileId,

            Long fileSize,

            Integer bitrate,

            Integer fps,

            String transcodeStatus
    ) { }
}
