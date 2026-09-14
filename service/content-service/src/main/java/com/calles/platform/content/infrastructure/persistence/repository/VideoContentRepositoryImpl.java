package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.infrastructure.persistence.entity.VideoContentPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoContentMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频内容聚合根仓储实现类 (VideoContentRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：基础设施层针对视频聚合根实体 {@link VideoContent} 的具体持久化适配；</li>
 *   <li><b>协作对象</b>：委托 {@link VideoContentMapper} 与底层数据库表 {@code video_content} 交互；</li>
 *   <li><b>技术特性</b>：在新增时保障 revision 初始版本号为 0；在更新时严格应用 CAS 乐观锁逻辑。</li>
 * </ul>
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class VideoContentRepositoryImpl implements VideoContentRepository {

    /** 视频数据访问 Mapper。 */
    private final VideoContentMapper videoContentMapper;

    @Override
    public int insert(VideoContent video) {
        // 步骤 1：转换为数据库持久化 PO 对象
        VideoContentPO po = VideoContentPO.fromDomain(video);
        // 步骤 2：保证乐观锁初始版本号为 0
        if (po.getRevision() == null) {
            po.setRevision(0L);
        }
        // 步骤 3：执行数据库新增
        return videoContentMapper.insert(po);
    }

    @Override
    public int updateById(VideoContent video) {
        // 步骤 1：转换领域模型为 PO 对象
        VideoContentPO po = VideoContentPO.fromDomain(video);
        // 步骤 2：执行带版本校验的乐观锁更新
        return videoContentMapper.updateWithOptimisticLock(po);
    }

    @Override
    public Optional<VideoContent> findById(String id) {
        // 步骤 1：根据主键查询有效记录 (MyBatis-Plus 自动追加 deleted=0)
        VideoContentPO po = videoContentMapper.selectById(id);
        // 步骤 2：转换为领域聚合根
        return Optional.ofNullable(po).map(VideoContentPO::toDomain);
    }

    @Override
    public Optional<VideoContent> findByVid(String vid) {
        // 步骤 1：按对外业务公开编码检索
        VideoContentPO po = videoContentMapper.selectByVid(vid);
        // 步骤 2：转换为领域聚合根
        return Optional.ofNullable(po).map(VideoContentPO::toDomain);
    }

    @Override
    public int deleteById(String id) {
        // 步骤 1：触发逻辑删除 (TableLogic 注解置位 deleted=1)
        return videoContentMapper.deleteById(id);
    }
}
