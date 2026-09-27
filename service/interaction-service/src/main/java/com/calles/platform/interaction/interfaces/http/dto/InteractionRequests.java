package com.calles.platform.interaction.interfaces.http.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 互动服务对外 HTTP 请求 DTO 汇总。
 */
public final class InteractionRequests {

    private InteractionRequests() {
    }

    /**
     * 播放心跳上报请求体。
     *
     * <p>不接收视频总时长：时长口径完全来自服务端本地快照，客户端无法通过上报时长影响播放量门槛与完播判定。</p>
     *
     * @param sessionId 服务端返回的会话 ID，首次心跳为空
     * @param sequence 客户端单调递增心跳序号，用于重复与乱序请求的幂等处理，可为空
     * @param position 当前播放头所在位置 (秒)
     * @param deltaDuration 距上次心跳增量秒数
     */
    public record Heartbeat(
            String sessionId,
            Long sequence,
            int position,
            Integer deltaDuration
    ) { }

    /**
     * 收藏视频请求体。
     *
     * @param folderId 目标收藏夹 ID (可选，为空时归入默认收藏夹)
     */
    public record Star(
            String folderId
    ) { }

    /**
     * 创建自定义收藏夹请求体。
     *
     * @param title 收藏夹名称标题 (必填，不超过64字符)
     */
    public record CreateFolder(
            @NotBlank(message = "收藏夹标题不能为空")
            @Size(max = 64, message = "收藏夹标题长度不能超过64字符")
            String title
    ) { }

    /**
     * 修改自定义收藏夹标题请求体。
     *
     * @param title 收藏夹新标题 (必填，不超过64字符)
     */
    public record UpdateFolder(
            @NotBlank(message = "收藏夹标题不能为空")
            @Size(max = 64, message = "收藏夹标题长度不能超过64字符")
            String title
    ) { }

    /**
     * 批量查询视频互动统计请求体。
     *
     * @param vids 待查询视频公开编码列表
     */
    public record BatchStats(
            List<String> vids
    ) { }
}
