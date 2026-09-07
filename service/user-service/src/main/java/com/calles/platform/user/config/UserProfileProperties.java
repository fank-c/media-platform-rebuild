package com.calles.platform.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户资料展示的运行参数。
 *
 * <p>头像前缀为空时，历史资料中的头像地址一律不对外展示，以免公开未受信任的 URL。</p>
 */
@ConfigurationProperties(prefix = "user.profile")
public class UserProfileProperties {

    /** 允许公开展示的头像 URL 前缀；允许为空。 */
    private String avatarAllowedPrefix = "";

    /** @return 去除首尾空白后的可信头像 URL 前缀 */
    public String getAvatarAllowedPrefix() {
        return avatarAllowedPrefix;
    }

    /** @param avatarAllowedPrefix 可信头像 URL 前缀；空值按不公开头像处理 */
    public void setAvatarAllowedPrefix(String avatarAllowedPrefix) {
        this.avatarAllowedPrefix = avatarAllowedPrefix == null ? "" : avatarAllowedPrefix.trim();
    }

    /** 启动期确保绑定后的前缀始终为非空字符串。 */
    public void validate() {
        if (avatarAllowedPrefix == null) {
            throw new IllegalStateException("User avatarAllowedPrefix must not be null");
        }
    }
}
