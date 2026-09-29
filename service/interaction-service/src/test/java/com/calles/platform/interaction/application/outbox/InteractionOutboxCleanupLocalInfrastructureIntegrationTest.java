package com.calles.platform.interaction.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 使用连接私有临时表验证真实 MySQL 删除 SQL，不访问或删除已有业务数据。 */
@EnabledIfEnvironmentVariable(named = "INTERACTION_OUTBOX_CLEANUP_IT_ENABLED", matches = "true")
class InteractionOutboxCleanupLocalInfrastructureIntegrationTest {

    /** 验证严格边界、状态保护、空发布时间、稳定顺序和重复执行。 */
    @Test
    void deletesOnlyOldPublishedInBoundedBatches() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                System.getenv("INTERACTION_OUTBOX_CLEANUP_IT_DB_URL"),
                System.getenv("INTERACTION_OUTBOX_CLEANUP_IT_DB_USERNAME"),
                System.getenv("INTERACTION_OUTBOX_CLEANUP_IT_DB_PASSWORD"));
        Configuration configuration = new Configuration(new Environment("cleanup-it",
                new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(InteractionOutboxMapper.class);
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            Connection connection = session.getConnection();
            // 临时表遮蔽同名业务表，连接关闭即销毁，不执行真实表的 DELETE。
            String ddl = Files.readString(Path.of("db/schema/interaction-outbox.sql"))
                    .replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMPORARY TABLE");
            try (var statement = connection.createStatement()) {
                statement.execute(ddl);
            }
            String first = insert(connection, "PUBLISHED", "2026-09-23T11:59:58Z");
            insert(connection, "PUBLISHED", "2026-09-23T11:59:59.999Z");
            insert(connection, "PUBLISHED", "2026-09-23T12:00:00Z");
            insert(connection, "PUBLISHED", "2026-09-23T12:00:01Z");
            insert(connection, "PUBLISHED", null);
            insert(connection, "PENDING", "2026-09-23T11:00:00Z");
            insert(connection, "PROCESSING", "2026-09-23T11:00:00Z");
            insert(connection, "FAILED", "2026-09-23T11:00:00Z");
            InteractionOutboxMapper mapper = session.getMapper(InteractionOutboxMapper.class);
            Timestamp cutoff = Timestamp.from(Instant.parse("2026-09-23T12:00:00Z"));

            assertThat(mapper.deletePublishedBefore("PUBLISHED", cutoff, 1)).isEqualTo(1);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM interaction_outbox WHERE event_id = ?")) {
                statement.setString(1, first);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
            assertThat(mapper.deletePublishedBefore("PUBLISHED", cutoff, 1)).isEqualTo(1);
            assertThat(mapper.deletePublishedBefore("PUBLISHED", cutoff, 1)).isZero();
            assertThat(mapper.countByStatus("PUBLISHED")).isEqualTo(3);
            assertThat(mapper.countByStatus("PENDING")).isEqualTo(1);
            assertThat(mapper.countByStatus("PROCESSING")).isEqualTo(1);
            assertThat(mapper.countByStatus("FAILED")).isEqualTo(1);
        }
    }

    /** 在当前连接临时表中插入一个测试事件，返回生成的事件 ID。 */
    private String insert(Connection connection, String status, String publishedAt) throws Exception {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO interaction_outbox
                    (event_id, aggregate_id, event_type, event_version, payload, occurred_at,
                     status, next_attempt_at, published_at)
                VALUES (?, 'cleanup_it', 'interaction.video-action', 1, '{}',
                        '2026-09-23 11:00:00.000', ?, '2026-09-23 11:00:00.000', ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, status);
            statement.setTimestamp(3, publishedAt == null ? null : Timestamp.from(Instant.parse(publishedAt)));
            statement.executeUpdate();
        }
        return id;
    }
}
