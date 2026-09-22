package com.calles.platform.user.infrastructure.persistence.mapper.follow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.user.domain.follow.UserCounter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户计数持久化 Mapper，采用原生 SQL 原子自增/自减，防止行锁长时争用与并发读写脏数据。
 */
@Mapper
public interface UserCounterMapper extends BaseMapper<UserCounter> {

    /**
     * 查询指定用户的关系统计快照。
     *
     * @param accountId 用户账号ID
     * @return 计数实体，若尚未初始化返回 null
     */
    @Select("SELECT account_id, following_count, follower_count, created_at, updated_at "
            + "FROM user_counter WHERE account_id = #{accountId} LIMIT 1")
    UserCounter selectByAccountId(@Param("accountId") String accountId);

    /**
     * 原子增加关注数（我关注了别人，我的 following_count + 1）。
     * 若当前用户计数行不存在，则基于 ON DUPLICATE KEY 自动初始化。
     *
     * @param accountId 用户账号ID
     * @return 影响行数
     */
    @Insert("INSERT INTO user_counter(account_id, following_count, follower_count, created_at, updated_at) "
            + "VALUES(#{accountId}, 1, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)) "
            + "ON DUPLICATE KEY UPDATE following_count = following_count + 1, updated_at = CURRENT_TIMESTAMP(3)")
    int incrFollowing(@Param("accountId") String accountId);

    /**
     * 原子减少关注数（我取消了关注，我的 following_count - 1）。
     * 采用 GREATEST(0, ...) 兜底防止出现负数异常。
     *
     * @param accountId 用户账号ID
     * @return 影响行数
     */
    @Update("UPDATE user_counter SET following_count = GREATEST(0, following_count - 1), "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE account_id = #{accountId}")
    int decrFollowing(@Param("accountId") String accountId);

    /**
     * 原子增加粉丝数（别人关注了我，我的 follower_count + 1）。
     * 若当前用户计数行不存在，则基于 ON DUPLICATE KEY 自动初始化。
     *
     * @param accountId 用户账号ID
     * @return 影响行数
     */
    @Insert("INSERT INTO user_counter(account_id, following_count, follower_count, created_at, updated_at) "
            + "VALUES(#{accountId}, 0, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)) "
            + "ON DUPLICATE KEY UPDATE follower_count = follower_count + 1, updated_at = CURRENT_TIMESTAMP(3)")
    int incrFollower(@Param("accountId") String accountId);

    /**
     * 原子减少粉丝数（别人取消关注我，我的 follower_count - 1）。
     * 采用 GREATEST(0, ...) 兜底防止出现负数异常。
     *
     * @param accountId 用户账号ID
     * @return 影响行数
     */
    @Update("UPDATE user_counter SET follower_count = GREATEST(0, follower_count - 1), "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE account_id = #{accountId}")
    int decrFollower(@Param("accountId") String accountId);
}
