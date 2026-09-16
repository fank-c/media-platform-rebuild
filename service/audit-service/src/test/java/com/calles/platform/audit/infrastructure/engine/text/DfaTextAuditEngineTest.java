package com.calles.platform.audit.infrastructure.engine.text;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.AuditSensitiveWord;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.CommonStatus;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.model.enums.WordCategory;
import com.calles.platform.audit.domain.model.enums.WordLevel;
import com.calles.platform.audit.domain.repository.AuditSensitiveWordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DFA 文本审查引擎基础设施实现单元测试。
 */
@ExtendWith(MockitoExtension.class)
class DfaTextAuditEngineTest {

    @Mock
    private AuditSensitiveWordRepository sensitiveWordRepository;

    private DfaTextAuditEngine engine;

    @BeforeEach
    void setUp() {
        when(sensitiveWordRepository.findByStatus(CommonStatus.ACTIVE)).thenReturn(List.of(
                AuditSensitiveWord.of("枪支", WordCategory.VIOLENCE, WordLevel.ILLEGAL),
                AuditSensitiveWord.of("弹药", WordCategory.VIOLENCE, WordLevel.ILLEGAL),
                AuditSensitiveWord.of("涉政违规", WordCategory.POLITICS, WordLevel.ILLEGAL),
                AuditSensitiveWord.of("疑似广告", WordCategory.AD, WordLevel.SUSPICIOUS)
        ));

        engine = new DfaTextAuditEngine(sensitiveWordRepository);
        engine.init();
    }

    @Test
    @DisplayName("验证 AuditEngine 顶层契约元数据")
    void shouldVerifyEngineMetadata() {
        assertThat(engine.getDimension()).isEqualTo(AuditDimension.TEXT);
        assertThat(engine.getEngineType()).isEqualTo("LOCAL_DFA");
        assertThat(engine.supports(AuditDimension.TEXT)).isTrue();
        assertThat(engine.supports(AuditDimension.IMAGE)).isFalse();
    }

    @Test
    @DisplayName("正常合规文本审查应直接通过")
    void normalTextShouldPass() {
        EngineAuditResult result = engine.audit("这是一个关于春天的美好生活记录短视频", AuditDimension.TEXT);

        assertThat(result.level()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(result.hitWords()).isEmpty();
        assertThat(result.dimension()).isEqualTo(AuditDimension.TEXT);
    }

    @Test
    @DisplayName("命中严重违禁词应判定为 ILLEGAL 并提取命中词条")
    void illegalTextShouldBeBlocked() {
        EngineAuditResult result = engine.audit("本视频包含非法枪支与涉政违规讨论", AuditDimension.TEXT);

        assertThat(result.level()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(result.hitWords()).contains("枪支", "涉政违规");
    }

    @Test
    @DisplayName("仅命中疑似词条应判定为 SUSPICIOUS 提示转人工")
    void suspiciousTextShouldTriggerManualReview() {
        EngineAuditResult result = engine.audit("关注主播不迷路，文末包含疑似广告推广链接", AuditDimension.TEXT);

        assertThat(result.level()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(result.hitWords()).contains("疑似广告");
    }

    @Test
    @DisplayName("文本含特殊干扰符和空格时仍能准确基于 DFA 匹配")
    void shouldHandleNoiseCharacters() {
        EngineAuditResult result = engine.audit("这里有 枪*支 和 涉_政_违_规", AuditDimension.TEXT);

        assertThat(result.level()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(result.hitWords()).isNotEmpty();
    }

    @Test
    @DisplayName("空字符串或 null 文本应安全放行")
    void emptyOrNullTextShouldPassSafely() {
        assertThat(engine.audit("", AuditDimension.TEXT).level()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(engine.audit(null, AuditDimension.TEXT).level()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(engine.audit("   ", AuditDimension.TEXT).level()).isEqualTo(ReviewLevel.NORMAL);
    }
}
