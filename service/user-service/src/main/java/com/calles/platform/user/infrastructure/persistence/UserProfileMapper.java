package com.calles.platform.user.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.user.domain.profile.UserProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户资料持久化入口；除生命周期专用查询外，默认遵循 MyBatis-Plus 逻辑删除过滤。
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /**
     * 查询物理记录并包含逻辑删除行，仅供初始化和生命周期判断，禁止直接暴露给接口层。
     */
    @Select("SELECT account_id,nickname,avatar_url,bio,city,gender,birthday,status,deleted,revision,created_at,updated_at "
            + "FROM user_profile WHERE account_id=#{accountId} LIMIT 1")
    UserProfile selectPhysicalById(@Param("accountId") String accountId);

    /**
     * 幂等创建默认资料；主键竞争返回 0，调用方必须重新读取生命周期而非吞掉其他数据库异常。
     */
    @Insert("INSERT IGNORE INTO user_profile(account_id,status,deleted,revision) VALUES(#{accountId},'ACTIVE',0,0)")
    int insertDefaultIfAbsent(@Param("accountId") String accountId);

    /**
     * 按版本局部更新正常资料。present 参数区分字段未提交与显式清空。
     */
    @Update("<script>UPDATE user_profile <set>"
            + "<if test='nicknamePresent'>nickname=#{nickname},</if>"
            + "<if test='bioPresent'>bio=#{bio},</if>"
            + "<if test='cityPresent'>city=#{city},</if>"
            + "<if test='birthdayPresent'>birthday=#{birthday},</if>"
            + "revision=revision+1,updated_at=CURRENT_TIMESTAMP(3)"
            + "</set> WHERE account_id=#{accountId} AND revision=#{revision} AND status='ACTIVE' AND deleted=0</script>")
    int updateEditableFields(@Param("accountId") String accountId, @Param("revision") long revision,
            @Param("nicknamePresent") boolean nicknamePresent, @Param("nickname") String nickname,
            @Param("bioPresent") boolean bioPresent, @Param("bio") String bio,
            @Param("cityPresent") boolean cityPresent, @Param("city") String city,
            @Param("birthdayPresent") boolean birthdayPresent, @Param("birthday") java.time.LocalDate birthday);
}
