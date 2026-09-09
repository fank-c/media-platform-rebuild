package com.calles.platform.file.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Instant;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/** V1 内容校验失败作废 SQL 的静态契约测试。 */
class FileAssetMapperV1ContentRejectionSqlTest {
  /** 作废 SQL 必须只命中未删除的 LEGACY_V1/PENDING，且不能附带自然到期条件。 */
  @Test
  void rejectionCasIsIndependentFromUploadDeadline() throws Exception {
    Method method = FileAssetMapper.class.getMethod("rejectPendingContent", String.class, Instant.class);
    String sql = method.getAnnotation(Update.class).value()[0];

    assertTrue(sql.contains("upload_status='EXPIRED'"));
    assertTrue(sql.contains("delete_requested_at IS NULL"));
    assertTrue(sql.contains("deleted_at IS NULL"));
    assertTrue(sql.contains("upload_protocol='LEGACY_V1'"));
    assertTrue(sql.contains("upload_status='PENDING'"));
    assertFalse(sql.contains("upload_expires_at <="));
  }
}
