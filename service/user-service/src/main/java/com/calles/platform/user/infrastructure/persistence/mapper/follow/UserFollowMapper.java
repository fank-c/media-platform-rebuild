package com.calles.platform.user.infrastructure.persistence.mapper.follow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.user.domain.follow.UserFollow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户关注关系持久化 Mapper，提供精准拓扑关系判定与高效覆盖索引分页检索。
 */
@Mapper
public interface UserFollowMapper extends BaseMapper<UserFollow> {

    /**
     * 查询指定发起人对指定目标用户的单向关注关系记录。
     *
     * @param userId 发起人用户ID
     * @param followId 目标用户ID
     * @return 关注关系实体，不存在返回 null
     */
    @Select("SELECT id, user_id, follow_id, follow_status, created_at, updated_at "
            + "FROM user_follow WHERE user_id = #{userId} AND follow_id = #{followId} LIMIT 1")
    UserFollow selectByPair(@Param("userId") String userId, @Param("followId") String followId);

    /**
     * 初始插入关注记录，唯一键冲突时忽略。
     *
     * @param userId 发起人用户ID
     * @param followId 目标用户ID
     * @param followStatus 初始状态
     * @return 影响行数
     */
    @Insert("INSERT IGNORE INTO user_follow(user_id, follow_id, follow_status, created_at, updated_at) "
            + "VALUES(#{userId}, #{followId}, #{followStatus}, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))")
    int insertIfAbsent(@Param("userId") String userId, @Param("followId") String followId,
                       @Param("followStatus") int followStatus);

    /**
     * 原子变更关注状态，附带状态前置条件保障幂等。
     *
     * @param userId 发起人用户ID
     * @param followId 目标用户ID
     * @param fromStatus 期望当前状态
     * @param toStatus 目标跃迁状态
     * @return 实际影响行数（1 表示成功跃迁，0 表示状态未变或行不存在）
     */
    @Update("UPDATE user_follow SET follow_status = #{toStatus}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE user_id = #{userId} AND follow_id = #{followId} AND follow_status = #{fromStatus}")
    int updateStatusConditionally(@Param("userId") String userId, @Param("followId") String followId,
                                  @Param("fromStatus") int fromStatus, @Param("toStatus") int toStatus);

    /**
     * 关注列表项目数据行载体。
     */
    record TargetFollowRow(String targetId, LocalDateTime followTime) {}

    /**
     * 分页查询指定用户的有效关注目标列表（按关注生效时间倒序）。
     *
     * @param userId 关注发起人ID
     * @param offset 偏移量
     * @param limit 条数
     * @return 关注目标与关注时间列表
     */
    @Select("SELECT follow_id AS targetId, updated_at AS followTime "
            + "FROM user_follow WHERE user_id = #{userId} AND follow_status = 1 "
            + "ORDER BY updated_at DESC LIMIT #{offset}, #{limit}")
    List<TargetFollowRow> selectFolloweeList(@Param("userId") String userId,
                                            @Param("offset") long offset,
                                            @Param("limit") long limit);

    /**
     * 统计指定用户的有效关注总数。
     *
     * @param userId 关注发起人ID
     * @return 关注总数
     */
    @Select("SELECT COUNT(1) FROM user_follow WHERE user_id = #{userId} AND follow_status = 1")
    long countFollowees(@Param("userId") String userId);

    /**
     * 分页查询指定用户的有效粉丝列表（按成为粉丝时间倒序）。
     *
     * @param followId 被关注者ID
     * @param offset 偏移量
     * @param limit 条数
     * @return 粉丝ID与关注时间列表
     */
    @Select("SELECT user_id AS targetId, updated_at AS followTime "
            + "FROM user_follow WHERE follow_id = #{followId} AND follow_status = 1 "
            + "ORDER BY updated_at DESC LIMIT #{offset}, #{limit}")
    List<TargetFollowRow> selectFollowersList(@Param("followId") String followId,
                                             @Param("offset") long offset,
                                             @Param("limit") long limit);

    /**
     * 统计指定用户的有效粉丝总数。
     *
     * @param followId 被关注者ID
     * @return 粉丝总数
     */
    @Select("SELECT COUNT(1) FROM user_follow WHERE follow_id = #{followId} AND follow_status = 1")
    long countFollowers(@Param("followId") String followId);

    /**
     * 提取指定用户关注的所有博主ID集合（供推荐通道高效批量召回）。
     *
     * @param userId 用户ID
     * @param maxLimit 最大拉取上限
     * @return 关注的博主账号ID列表
     */
    @Select("SELECT follow_id FROM user_follow WHERE user_id = #{userId} AND follow_status = 1 "
            + "ORDER BY updated_at DESC LIMIT #{maxLimit}")
    List<String> selectAllFolloweeIds(@Param("userId") String userId, @Param("maxLimit") int maxLimit);
}
