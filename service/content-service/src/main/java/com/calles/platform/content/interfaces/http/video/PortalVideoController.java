package com.calles.platform.content.interfaces.http.video;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.content.application.security.ContentAccessPolicy;
import com.calles.platform.content.application.video.VideoQueryApplicationService;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台消费端（点播与浏览）HTTP API 控制器。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：面向最终点播用户（移动端 App、Web 门户、开放客户端）的视频内容消费接口适配；</li>
 *   <li><b>核心用例</b>：根据业务公开编码 {@code vid} 获取视频图文详情、检索转码就绪的多清晰度播放流列表；</li>
 *   <li><b>鉴权要求</b>：支持匿名未登录游客访问（仅可见 PUBLISHED 且 PUBLIC 的视频），也支持已登录普通用户与管理员访问（通过门禁按作者本人或管理员权限放行草稿与私有视频）；</li>
 *   <li><b>不应承担的工作</b>：不处理视频创作发布修改操作，不承担管理员风控与转码回调逻辑。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/content/videos")
@RequiredArgsConstructor
public class PortalVideoController {

    /** 视频多维度读模型查询服务。 */
    private final VideoQueryApplicationService queryService;

    /** 统一认证上下文与权限门禁策略。 */
    private final ContentAccessPolicy accessPolicy;

    /**
     * 根据业务公开编码 vid 获取视频完整图文与指标详情。
     *
     * @param vid 24 位业务唯一公开短码 (如 cv05hG9Kq2RtLw7XbPmZv4Ya)
     * @return 视频图文详情响应
     */
    @GetMapping("/{vid}")
    public ApiResponse<VideoResponses.Detail> getVideoDetail(@PathVariable String vid) {
        // 步骤 1：尝试提取当前访问用户身份（支持未登录匿名访问）
        Optional<UserInfo> userOpt = accessPolicy.getCurrentUser();
        String userId = userOpt.map(UserInfo::userId).orElse(null);
        boolean isAdmin = userOpt.map(UserInfo::isAdmin).orElse(false);
        // 步骤 2：根据可见性范围、发布状态及封禁状态进行门禁判别后返回详情
        return ApiResponse.ok(queryService.getVideoDetail(vid, userId, isAdmin));
    }

    /**
     * 根据业务公开编码 vid 获取已就绪的可用转码播放流列表。
     *
     * @param vid 24 位业务唯一公开短码
     * @return 包含全部转码完成清晰度切片（1080P/720P/4K 等）的播放流响应
     */
    @GetMapping("/{vid}/streams")
    public ApiResponse<VideoResponses.PlayStreams> getPlayStreams(@PathVariable String vid) {
        // 步骤 1：获取当前主体（未登录游客或登录用户）
        Optional<UserInfo> userOpt = accessPolicy.getCurrentUser();
        String userId = userOpt.map(UserInfo::userId).orElse(null);
        boolean isAdmin = userOpt.map(UserInfo::isAdmin).orElse(false);
        // 步骤 2：校验访问权限后返回已就绪的转码切片资产列表
        return ApiResponse.ok(queryService.getPlayStreams(vid, userId, isAdmin));
    }
}
