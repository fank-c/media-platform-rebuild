package com.calles.platform.audit.infrastructure.engine.rule;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资产规则审查引擎抽象基类及派生实现单元测试。
 */
class AbstractRuleAssetAuditEngineTest {

    private final DefaultRuleImageAuditEngine imageEngine = new DefaultRuleImageAuditEngine();
    private final DefaultVideoAuditEngine videoEngine = new DefaultVideoAuditEngine();

    @Test
    @DisplayName("图片规则引擎元数据与支持维度契约断言")
    void imageEngineMetadataAndSupports() {
        assertThat(imageEngine.getDimension()).isEqualTo(AuditDimension.IMAGE);
        assertThat(imageEngine.getEngineType()).isEqualTo(DefaultRuleImageAuditEngine.ENGINE_NAME);
        assertThat(imageEngine.supports(AuditDimension.IMAGE)).isTrue();
        assertThat(imageEngine.supports(AuditDimension.VIDEO)).isFalse();
        assertThat(imageEngine.supports(null)).isFalse();
    }

    @Test
    @DisplayName("图片规则引擎生命周期：空资产、违禁特征、疑似特征及合规放行")
    void imageEngineRuleEvaluation() {
        // 空资产
        EngineAuditResult emptyRes = imageEngine.audit(null);
        assertThat(emptyRes.level()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(emptyRes.hitWords()).contains("MISSING_COVER");

        EngineAuditResult blankRes = imageEngine.audit("   ");
        assertThat(blankRes.level()).isEqualTo(ReviewLevel.ILLEGAL);

        // 违禁特征
        EngineAuditResult illegalRes = imageEngine.audit("cover_violation_sample.jpg");
        assertThat(illegalRes.level()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(illegalRes.hitWords()).contains("IMAGE_PORN_OR_VIOLATION");

        // 疑似特征
        EngineAuditResult suspiciousRes = imageEngine.audit("cover_suspicious_scene.png");
        assertThat(suspiciousRes.level()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(suspiciousRes.hitWords()).contains("IMAGE_SUSPICIOUS");

        // 正常放行
        EngineAuditResult normalRes = imageEngine.audit("clean_sunny_landscape.jpg");
        assertThat(normalRes.level()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(normalRes.hitWords()).isEmpty();

        // 兼容旧版 auditCover 方法
        EngineAuditResult compatRes = imageEngine.auditCover("clean_sunny_landscape.jpg");
        assertThat(compatRes.level()).isEqualTo(ReviewLevel.NORMAL);
    }

    @Test
    @DisplayName("视频规则引擎元数据与支持维度契约断言")
    void videoEngineMetadataAndSupports() {
        assertThat(videoEngine.getDimension()).isEqualTo(AuditDimension.VIDEO);
        assertThat(videoEngine.getEngineType()).isEqualTo(DefaultVideoAuditEngine.ENGINE_NAME);
        assertThat(videoEngine.supports(AuditDimension.VIDEO)).isTrue();
        assertThat(videoEngine.supports(AuditDimension.TEXT)).isFalse();
    }

    @Test
    @DisplayName("视频规则引擎生命周期：空资产、违禁特征、疑似特征及合规放行")
    void videoEngineRuleEvaluation() {
        // 空资产
        EngineAuditResult emptyRes = videoEngine.audit(null);
        assertThat(emptyRes.level()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(emptyRes.hitWords()).contains("MISSING_VIDEO_FILE");

        // 违禁特征
        EngineAuditResult illegalRes = videoEngine.audit("stream_illegal_test.mp4");
        assertThat(illegalRes.level()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(illegalRes.hitWords()).contains("VIDEO_CONTENT_ILLEGAL");

        // 疑似特征
        EngineAuditResult suspiciousRes = videoEngine.audit("stream_suspicious_clip.mp4");
        assertThat(suspiciousRes.level()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(suspiciousRes.hitWords()).contains("VIDEO_CONTENT_SUSPICIOUS");

        // 正常放行
        EngineAuditResult normalRes = videoEngine.audit("normal_vlog_record.mp4");
        assertThat(normalRes.level()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(normalRes.hitWords()).isEmpty();

        // 兼容旧版 auditVideo 方法
        EngineAuditResult compatRes = videoEngine.auditVideo("normal_vlog_record.mp4");
        assertThat(compatRes.level()).isEqualTo(ReviewLevel.NORMAL);
    }
}
