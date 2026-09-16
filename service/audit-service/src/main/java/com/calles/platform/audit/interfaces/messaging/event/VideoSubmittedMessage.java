package com.calles.platform.audit.interfaces.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 视频提审事件消息体数据绑定模型。
 *
 * <p>职责与兼容性说明：
 * <ul>
 *   <li>支持标准的通用事件信封包装格式（包含 eventId、eventType、traceId 以及嵌套的 payload 载荷对象）；</li>
 *   <li>平滑容错扁平未包装的 JSON 结构（直接声明 videoId 等属性）；</li>
 *   <li>开启 {@code ignoreUnknown = true}，容忍未知或拓展字段，满足契约向前兼容性要求。</li>
 * </ul>
 * </p>
 *
 * @param eventId 平台统一事件唯一 UUID
 * @param eventType 领域事件类型名称 (如 content.video.submitted)
 * @param traceId 链路追踪标识 (Trace ID)
 * @param payload 标准信封内嵌业务载荷对象（若存在）
 * @param videoId 扁平报文中的视频业务主键 ID（兼容容错）
 * @param vid 扁平报文中的视频公开短码（兼容容错）
 * @param authorId 扁平报文中的创作者用户 ID（兼容容错）
 * @param title 扁平报文中的视频标题快照（兼容容错）
 * @param description 扁平报文中的视频简介快照（兼容容错）
 * @param coverFileId 扁平报文中的封面图片文件资产 ID（兼容容错）
 * @param videoFileId 扁平报文中的主视频文件资产 ID（兼容容错）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoSubmittedMessage(
        String eventId,
        String eventType,
        String traceId,
        VideoSubmittedPayload payload,
        String videoId,
        String vid,
        String authorId,
        String title,
        String description,
        String coverFileId,
        String videoFileId
) {
    /**
     * 解析并获取最终的有效视频提审载荷。
     *
     * <p>优先使用嵌套的 {@link #payload} 对象；若为扁平报文则从顶层属性构造载荷。</p>
     *
     * @return 提审业务载荷对象
     */
    public VideoSubmittedPayload resolvePayload() {
        if (payload != null) {
            return payload;
        }
        return new VideoSubmittedPayload(
                videoId,
                vid,
                authorId,
                title,
                description,
                coverFileId,
                videoFileId
        );
    }
}
