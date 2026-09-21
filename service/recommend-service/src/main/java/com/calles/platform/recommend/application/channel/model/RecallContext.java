package com.calles.platform.recommend.application.channel.model;

import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 推荐多路召回上下文对象 (RecallContext)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务应用层数据传输载荷；</li>
 *   <li><b>数据承载</b>：封装单次推荐请求所依赖的用户身份、双层偏好画像、显式屏蔽黑名单与请求槽位配额；</li>
 *   <li><b>生命周期</b>：请求级不可变（Immutable）对象，由推荐编排层在单次 Feed 请求初期装配并跨通道共享，避免下游重复拉库导致数据倾斜。</li>
 * </ul>
 * </p>
 */
@Getter
@Builder
public class RecallContext {

    /** 请求用户全局账号 ID (游客访问时为 null，已登录时为 32/36 位 UUID)。 */
    private final String userId;

    /** 用户个性化偏好画像聚合根 (包含双层标签权重与兴趣向量，未登录或新用户无行为时为 null)。 */
    private final UserProfile userProfile;

    /** 用户显式屏蔽项黑名单列表 (包含拉黑的特定视频短码、特定作者 ID 或敏感主题标签)。 */
    private final List<UserBlock> userBlocks;

    /** 本次推荐请求期望最终交付的有效卡片总槽位数量 (默认 10，上限 50)。 */
    private final int targetTotalSize;

    /**
     * 判断当前上下文是否属于已登录的注册用户。
     *
     * @return true 表示为有效登录态用户，false 表示游客未登录访问
     */
    public boolean isLogin() {
        return userId != null && !userId.isBlank();
    }

    /**
     * 获取安全非空的屏蔽列表，防御空指针异常。
     *
     * @return 用户屏蔽黑名单列表，未配置时安全返回不可变空列表
     */
    public List<UserBlock> getSafeUserBlocks() {
        return userBlocks != null ? userBlocks : Collections.emptyList();
    }
}
