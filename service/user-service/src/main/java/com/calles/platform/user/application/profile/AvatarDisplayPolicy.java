package com.calles.platform.user.application.profile;

import com.calles.platform.user.config.UserProfileProperties;
import org.springframework.stereotype.Component;

/**
 * 头像公开展示策略；当前只允许明确配置的可信资源前缀，不发起服务器端 URL 抓取。
 */
@Component
public class AvatarDisplayPolicy {

    /** 可信头像 URL 前缀；为空时保守隐藏全部历史头像值。 */
    private final String allowedPrefix;

    /** 创建头像策略。 */
    public AvatarDisplayPolicy(UserProfileProperties properties) {
        this.allowedPrefix = properties.getAvatarAllowedPrefix();
    }

    /**
     * 返回可公开展示的头像地址；未建立文件引用契约前，不可信值统一返回空。
     */
    public String publicUrl(String storedUrl) {
        if (storedUrl == null || storedUrl.isBlank() || allowedPrefix.isBlank()) {
            return null;
        }
        return storedUrl.startsWith(allowedPrefix) ? storedUrl : null;
    }
}
