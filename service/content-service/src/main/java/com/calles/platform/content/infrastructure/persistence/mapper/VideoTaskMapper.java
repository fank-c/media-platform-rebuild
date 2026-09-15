package com.calles.platform.content.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.content.infrastructure.persistence.entity.VideoTaskPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 视频异步流水线子任务数据访问 Mapper (VideoTaskMapper)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：基础设施层针对 MySQL {@code video_task} 表的数据访问层 (DAO)；</li>
 *   <li><b>主要职责</b>：基于 MyBatis-Plus 继承基础 CRUD，扩展自定义针对任务按视频维度查询、类型排他检索及超时扫描的 SQL；</li>
 *   <li><b>不应承担的工作</b>：不参与任何业务决策，仅执行数据库物理读写。</li>
 * </ul>
 * </p>
 */
@Mapper
public interface VideoTaskMapper extends BaseMapper<VideoTaskPO> {

    /**
     * 根据视频内部主键 ID 查询其名下的全部流水线子任务列表。
     *
     * <p>按创建时间升序排列，以便依序观察任务初始化网格。</p>
     *
     * @param videoId 关联的视频全局唯一主键 ID (UUID 32位)
     * @return 匹配的任务持久化实体列表
     */
    @Select("SELECT * FROM video_task WHERE video_id = #{videoId} ORDER BY created_at ASC")
    List<VideoTaskPO> selectByVideoId(@Param("videoId") String videoId);

    /**
     * 根据视频主键 ID 和任务类型字面量精确定位唯一的子任务记录。
     *
     * @param videoId 视频全局唯一主键 ID
     * @param taskType 任务类型字符串编码 (如 AUDIT, TRANSCODE_720P)
     * @return 匹配的任务持久化实体，未找到时返回 null
     */
    @Select("SELECT * FROM video_task WHERE video_id = #{videoId} AND task_type = #{taskType} LIMIT 1")
    VideoTaskPO selectByVideoIdAndTaskType(@Param("videoId") String videoId, @Param("taskType") String taskType);

    /**
     * 检索指定运行状态且启动时间早于指定阈值的超时任务记录列表。
     *
     * <p><b>索引利用</b>：利用 {@code idx_task_status_started (status, started_at)} 联合索引高效扫描。</p>
     *
     * @param status 目标过滤任务状态编码 (通常为 RUNNING)
     * @param startedBefore 启动时间阈值 (started_at &lt;= #{startedBefore})
     * @return 超时未响应的任务持久化实体列表
     */
    @Select("SELECT * FROM video_task WHERE status = #{status} AND started_at IS NOT NULL AND started_at <= #{startedBefore}")
    List<VideoTaskPO> selectTimeoutTasks(@Param("status") String status, @Param("startedBefore") LocalDateTime startedBefore);

    /**
     * 物理删除指定视频名下的所有流水线任务记录。
     *
     * <p>仅用于测试环境或历史归档物理清理。</p>
     *
     * @param videoId 视频全局唯一主键 ID
     * @return 数据库受影响并删除的行数
     */
    @Delete("DELETE FROM video_task WHERE video_id = #{videoId}")
    int deleteByVideoId(@Param("videoId") String videoId);
}
