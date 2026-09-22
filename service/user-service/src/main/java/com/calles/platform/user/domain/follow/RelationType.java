package com.calles.platform.user.domain.follow;

/**
 * 双方社交关系类型枚举，表达登录用户与目标用户之间的拓扑图关系。
 */
public enum RelationType {

    /** 互无关系（未关注且未被关注）。 */
    NONE,

    /** 当前用户关注了目标用户（单向关注）。 */
    FOLLOWING,

    /** 目标用户关注了当前用户（当前用户是其粉丝）。 */
    FOLLOWED_BY,

    /** 互相关注（好友关系）。 */
    MUTUAL
}
