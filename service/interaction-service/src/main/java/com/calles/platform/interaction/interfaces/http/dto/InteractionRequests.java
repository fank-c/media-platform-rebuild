package com.calles.platform.interaction.interfaces.http.dto;

import jakarta.validation.constraints.NotBlank;
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
     * @param position 当前播放头所在位置 (秒)
     * @param deltaDuration 距上次心跳增量秒数 (如 5)
     * @param videoDuration 视频总时长 (秒)
     */
    public record Heartbeat(
            int position,
            int deltaDuration,
            int videoDuration
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
     * @param title 收藏夹名称标题 (必填)
     */
    public record CreateFolder(
            @NotBlank(message = "收藏夹标题不能为空")
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
