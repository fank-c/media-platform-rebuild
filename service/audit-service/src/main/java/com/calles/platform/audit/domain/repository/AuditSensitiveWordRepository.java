package com.calles.platform.audit.domain.repository;

import com.calles.platform.audit.domain.model.AuditSensitiveWord;
import com.calles.platform.audit.domain.model.enums.CommonStatus;

import java.util.List;
import java.util.Optional;

/**
 * 敏感词字典仓储接口。
 *
 * <p>职责定义：负责敏感词库持久化数据的增删改查，为本地 DFA 状态机热加载与动态规则更新提供数据源支持。</p>
 * <p>所属边界：审核服务领域仓储层，仅面向 {@link AuditSensitiveWord} 实体，不参与树构建与匹配计算。</p>
 */
public interface AuditSensitiveWordRepository {

    /**
     * 新增敏感词词条。
     *
     * @param word 敏感词实体（包含词文本、分类、风险拦截级别与生效状态）
     * @return 数据库受影响行数（1 为新增成功）
     */
    int insert(AuditSensitiveWord word);

    /**
     * 查询所有处于特定状态的敏感词列表（通常用于服务启动或动态刷新时重建内存 DFA 前缀树）。
     *
     * @param status 词条生效状态（如 ACTIVE 正常生效，DISABLED 停用）
     * @return 匹配状态的敏感词列表；若无匹配返回空集合而非 null
     */
    List<AuditSensitiveWord> findByStatus(CommonStatus status);

    /**
     * 根据敏感词文本精确查找单条记录。
     *
     * @param word 词条文本（区分大小写或已标准化小写）
     * @return 封装敏感词实体的 Optional，若不存在则为 Optional.empty()
     */
    Optional<AuditSensitiveWord> findByWord(String word);

    /**
     * 根据主键 ID 物理删除敏感词。
     *
     * @param id 敏感词主键 ID
     * @return 数据库受影响行数
     */
    int deleteById(String id);
}
