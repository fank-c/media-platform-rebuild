package com.calles.platform.audit.infrastructure.engine.impl;

import com.calles.platform.audit.domain.engine.TextAuditEngine;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.AuditSensitiveWord;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.CommonStatus;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.model.enums.WordCategory;
import com.calles.platform.audit.domain.model.enums.WordLevel;
import com.calles.platform.audit.domain.repository.AuditSensitiveWordRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于确定有限状态机 (DFA) 算法的高性能本地敏感词与合规审查引擎基础设施实现。
 *
 * <p>核心特点：
 * <ul>
 *   <li>实现 {@link TextAuditEngine} 契约规范，审查维度固定为 {@link AuditDimension#TEXT}；</li>
 *   <li>时间复杂度为 O(N)，匹配速度不受词库规模膨胀影响；</li>
 *   <li>支持多级威胁判定：命中 {@link WordLevel#ILLEGAL} 直接阻断，命中 {@link WordLevel#SUSPICIOUS} 升级人审；</li>
 *   <li>支持数据库敏感词字典热加载与默认应急安全打底词库。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class DfaTextAuditEngine implements TextAuditEngine {

    /** 引擎标识名称。 */
    public static final String ENGINE_NAME = "LOCAL_DFA";
    private static final String IS_END = "isEnd";
    private static final String WORD_LEVEL = "wordLevel";

    private final AuditSensitiveWordRepository sensitiveWordRepository;

    /** DFA 敏感词树根节点。 */
    private Map<Object, Object> sensitiveWordTree = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public DfaTextAuditEngine(AuditSensitiveWordRepository sensitiveWordRepository) {
        this.sensitiveWordRepository = sensitiveWordRepository;
    }

    public DfaTextAuditEngine() {
        this.sensitiveWordRepository = null;
    }

    @Override
    public AuditDimension getDimension() {
        return AuditDimension.TEXT;
    }

    @Override
    public String getEngineType() {
        return ENGINE_NAME;
    }

    @PostConstruct
    public synchronized void init() {
        reloadWords();
    }

    /**
     * 热重载敏感词库，并重新构建内存 DFA 前缀树。
     */
    public synchronized void reloadWords() {
        List<AuditSensitiveWord> words = null;
        try {
            if (sensitiveWordRepository != null) {
                words = sensitiveWordRepository.findByStatus(CommonStatus.ACTIVE);
            }
        } catch (Exception e) {
            log.warn("从持久化仓储加载敏感词失败，将使用默认兜底词库: {}", e.getMessage());
        }

        if (words == null || words.isEmpty()) {
            words = getDefaultBuiltinWords();
        }

        Map<Object, Object> newTree = new HashMap<>();
        for (AuditSensitiveWord item : words) {
            if (item.getWord() == null || item.getWord().isBlank()) {
                continue;
            }
            insertWordToTree(newTree, item.getWord().trim().toLowerCase(), item.getLevel());
        }
        this.sensitiveWordTree = newTree;
        log.info("DFA 敏感词库加载完成，有效词条总数: [{}]", words.size());
    }

    @Override
    public EngineAuditResult audit(String text, AuditDimension dimension) {
        if (text == null || text.isBlank()) {
            return EngineAuditResult.normal(dimension != null ? dimension : AuditDimension.TEXT, ENGINE_NAME, "文本内容为空，通过");
        }

        AuditDimension targetDim = dimension != null ? dimension : AuditDimension.TEXT;
        String normalizedText = text.toLowerCase();
        Set<String> hitIllegalWords = new HashSet<>();
        Set<String> hitSuspiciousWords = new HashSet<>();

        // 步骤 1：遍历待检测文本，基于 DFA 状态转移寻找敏感词
        for (int i = 0; i < normalizedText.length(); i++) {
            int matchLength = checkMatchLength(normalizedText, i);
            if (matchLength > 0) {
                String hitWord = normalizedText.substring(i, i + matchLength);
                WordLevel level = getMatchWordLevel(normalizedText, i, matchLength);
                if (level == WordLevel.ILLEGAL) {
                    hitIllegalWords.add(hitWord);
                } else {
                    hitSuspiciousWords.add(hitWord);
                }
                // 跳跃指针
                i = i + matchLength - 1;
            }
        }

        // 步骤 2：综合评定风险等级与命中文本
        if (!hitIllegalWords.isEmpty()) {
            List<String> allHits = new ArrayList<>(hitIllegalWords);
            allHits.addAll(hitSuspiciousWords);
            String logMsg = String.format("命中严重违规违禁词: %s", hitIllegalWords);
            return EngineAuditResult.of(
                    targetDim,
                    ENGINE_NAME,
                    ReviewLevel.ILLEGAL,
                    BigDecimal.valueOf(99.00),
                    allHits,
                    logMsg
            );
        }

        if (!hitSuspiciousWords.isEmpty()) {
            List<String> hits = new ArrayList<>(hitSuspiciousWords);
            String logMsg = String.format("命中疑似可疑词条，建议转人审: %s", hitSuspiciousWords);
            return EngineAuditResult.of(
                    targetDim,
                    ENGINE_NAME,
                    ReviewLevel.SUSPICIOUS,
                    BigDecimal.valueOf(75.00),
                    hits,
                    logMsg
            );
        }

        return EngineAuditResult.normal(targetDim, ENGINE_NAME, "文本合规，未命中任何敏感词");
    }

    /**
     * 向 DFA 树插入单个敏感词。
     */
    @SuppressWarnings("unchecked")
    private void insertWordToTree(Map<Object, Object> tree, String word, WordLevel level) {
        Map<Object, Object> current = tree;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            Map<Object, Object> subTree = (Map<Object, Object>) current.get(c);
            if (subTree == null) {
                subTree = new HashMap<>();
                current.put(c, subTree);
            }
            current = subTree;
            if (i == word.length() - 1) {
                current.put(IS_END, "1");
                current.put(WORD_LEVEL, level != null ? level : WordLevel.ILLEGAL);
            }
        }
    }

    /**
     * 从指定起始位置匹配敏感词长度（最大匹配原则）。
     */
    @SuppressWarnings("unchecked")
    private int checkMatchLength(String text, int beginIndex) {
        int matchLength = 0;
        int tempLength = 0;
        Map<Object, Object> current = this.sensitiveWordTree;

        for (int i = beginIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            // 忽略空白与常见干扰字符
            if (isIgnoredChar(c)) {
                tempLength++;
                continue;
            }

            current = (Map<Object, Object>) current.get(c);
            if (current != null) {
                tempLength++;
                if ("1".equals(current.get(IS_END))) {
                    matchLength = tempLength;
                }
            } else {
                break;
            }
        }
        return matchLength;
    }

    /**
     * 获取指定匹配区间的词条严重等级。
     */
    @SuppressWarnings("unchecked")
    private WordLevel getMatchWordLevel(String text, int beginIndex, int matchLength) {
        Map<Object, Object> current = this.sensitiveWordTree;
        int count = 0;
        for (int i = beginIndex; i < beginIndex + matchLength; i++) {
            char c = text.charAt(i);
            if (isIgnoredChar(c)) {
                continue;
            }
            current = (Map<Object, Object>) current.get(c);
            if (current == null) {
                break;
            }
            count++;
        }
        if (current != null && current.get(WORD_LEVEL) instanceof WordLevel wl) {
            return wl;
        }
        return WordLevel.ILLEGAL;
    }

    private boolean isIgnoredChar(char c) {
        return Character.isWhitespace(c) || c == '*' || c == '#' || c == '-' || c == '_' || c == '.' || c == ',';
    }

    /**
     * 内置默认应急词库，保证无外部数据源时系统仍具基础自御防线。
     */
    private List<AuditSensitiveWord> getDefaultBuiltinWords() {
        List<AuditSensitiveWord> list = new ArrayList<>();
        list.add(AuditSensitiveWord.of("违禁测试词", WordCategory.GENERAL, WordLevel.ILLEGAL));
        list.add(AuditSensitiveWord.of("枪支弹药", WordCategory.VIOLENCE, WordLevel.ILLEGAL));
        list.add(AuditSensitiveWord.of("办假证", WordCategory.AD, WordLevel.ILLEGAL));
        list.add(AuditSensitiveWord.of("涉黄低俗", WordCategory.PORN, WordLevel.ILLEGAL));
        list.add(AuditSensitiveWord.of("疑似广告推广", WordCategory.AD, WordLevel.SUSPICIOUS));
        list.add(AuditSensitiveWord.of("涉嫌争议话题", WordCategory.POLITICS, WordLevel.SUSPICIOUS));
        return list;
    }
}
