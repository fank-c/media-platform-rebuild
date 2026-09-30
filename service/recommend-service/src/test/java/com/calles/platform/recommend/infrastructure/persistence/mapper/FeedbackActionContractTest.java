package com.calles.platform.recommend.infrastructure.persistence.mapper;

import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 行为动作与热度查询的契约回归，防止删除 HTTP 上报后仍依赖客户端动作。 */
class FeedbackActionContractTest {

    /** 客户端旧动作不能作为有效互动事实解析；MQ 原始动作保持独立。 */
    @Test
    void onlyAcceptsServerEventActions() {
        for (String action : new String[]{"IMPRESSION", "PLAY", "SKIP", "DISLIKE"}) {
            assertThatThrownBy(() -> FeedbackActionType.fromCode(action))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(FeedbackActionType.values()).extracting(FeedbackActionType::getCode)
                .containsExactly("LIKE", "UNLIKE", "STAR", "UNSTAR", "SHARE",
                        "WATCH_VIEW_QUALIFIED", "WATCH_COMPLETED", "FOLLOW", "UNFOLLOW");
    }

    /** 空库与既有约束脚本必须允许相同的 MQ 动作，避免保留旧入口的动作值。 */
    @Test
    void schemaConstraintsMatchServerActions() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("db/init/schema.sql"))) {
            root = root.getParent();
        }
        assertThat(root).as("定位仓库初始化脚本").isNotNull();
        Pattern constraint = Pattern.compile("ck_rfl_action_type` CHECK \\(.*? IN \\((.*?)\\)\\)",
                Pattern.DOTALL);
        Pattern quotedValue = Pattern.compile("'([^']+)'");
        for (String path : new String[]{"db/init/schema.sql",
                "service/recommend-service/db/schema/recommend-user-model.sql",
                "service/recommend-service/db/migration/V1.1__expand_feedback_action_type_constraint.sql"}) {
            var matcher = constraint.matcher(Files.readString(root.resolve(path)));
            assertThat(matcher.find()).as("%s 包含动作约束", path).isTrue();
            var actions = quotedValue.matcher(matcher.group(1)).results()
                    .map(result -> result.group(1)).toList();
            assertThat(actions).as(path).containsExactly(
                    java.util.Arrays.stream(FeedbackActionType.values())
                            .map(FeedbackActionType::getCode).toArray(String[]::new));
        }
    }

    /** 热度只统计有效观看，不把同会话的完播再次计入，也不依赖已删除的 PLAY。 */
    @Test
    void trendingCountsOnlyQualifiedWatches() throws Exception {
        Select select = FeedbackLogMapper.class
                .getMethod("selectTopVidsByPlays", LocalDateTime.class, int.class)
                .getAnnotation(Select.class);
        String sql = String.join("\n", select.value());
        assertThat(sql).contains("action_type = 'WATCH_VIEW_QUALIFIED'")
                .doesNotContain("'PLAY'", "'WATCH_COMPLETED'");
    }
}
