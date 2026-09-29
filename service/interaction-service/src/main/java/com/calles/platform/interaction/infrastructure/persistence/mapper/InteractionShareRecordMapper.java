package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.InteractionShareRecordPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 视频分享幂等记录数据访问 Mapper。
 */
@Mapper
public interface InteractionShareRecordMapper extends BaseMapper<InteractionShareRecordPO> {

    /**
     * 采用当前读（Locking Read FOR UPDATE）查询用户在指定幂等键下的分享记录。
     *
     * <p>绕过 MySQL REPEATABLE READ 隔离级别下的 MVCC 快照读限制，
     * 确保在并发写入引发唯一键冲突时，能即时读取已由胜出事务提交的最新行数据。</p>
     *
     * @param userId 操作用户账号 ID
     * @param idempotencyKey 客户端请求幂等键
     * @return 分享记录持久化实体 (未命中返回 null)
     */
    @Select("SELECT * FROM interaction_share_record WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} AND deleted = 0 LIMIT 1 FOR UPDATE")
    InteractionShareRecordPO selectByUserIdAndIdempotencyKeyForUpdate(@Param("userId") String userId,
                                                                      @Param("idempotencyKey") String idempotencyKey);
}
