package com.calles.platform.file.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/** file_asset 删除闸门 SQL 的静态契约测试，防止后续修改遗漏可见性和完成 CAS 条件。 */
class FileAssetMapperDeletionGateSqlTest {
  /** 可见查询必须同时排除删除中和已删除记录，避免向客户端暴露中间状态。 */
  @Test
  void visibleQueryHidesDeletionRequestedRecords() throws Exception {
    Method method =
        FileAssetMapper.class.getMethod("selectVisibleByIdAndOwner", String.class, String.class);
    String sql = method.getAnnotation(Select.class).value()[0];

    assertTrue(sql.contains("delete_requested_at IS NULL"));
    assertTrue(sql.contains("deleted_at IS NULL"));
  }

  /** 在途确认的最终 CAS 必须检查删除闸门，不能在远端读取后重新完成已删除文件。 */
  @Test
  void completionCasCannotCrossDeletionGate() throws Exception {
    Method method =
        FileAssetMapper.class.getMethod(
            "complete",
            String.class,
            String.class,
            long.class,
            long.class,
            String.class,
            com.calles.platform.file.domain.asset.StorageType.class,
            String.class,
            java.time.Instant.class);
    String sql = method.getAnnotation(Update.class).value()[0];

    assertTrue(sql.contains("delete_requested_at IS NULL"));
    assertTrue(sql.contains("deleted_at IS NULL"));
  }

  /** V2 完成 CAS 必须限定 VERIFYING、ETag 和删除闸门，不能把 copy 结果直接写成完成。 */
  @Test
  void v2CompletionCasUsesVerificationGateAndEtag() throws Exception {
    Method method =
        FileAssetMapper.class.getMethod(
            "completeVerification",
            String.class,
            String.class,
            long.class,
            long.class,
            String.class,
            String.class,
            com.calles.platform.file.domain.asset.StorageType.class,
            String.class,
            String.class,
            java.time.Instant.class);
    String sql = method.getAnnotation(Update.class).value()[0];

    assertTrue(sql.contains("upload_status='VERIFYING'"));
    assertTrue(sql.contains("expected_sha256=#{expectedSha256}"));
    assertTrue(sql.contains("verified_source_etag=#{etag}"));
    assertTrue(sql.contains("delete_requested_at IS NULL"));
    assertTrue(sql.contains("deleted_at IS NULL"));
  }

  /** V2 内容不匹配必须在上传期限内立即作废，而不是复用仅处理自然到期的 SQL。 */
  @Test
  void rejectionCasCanInvalidateVerifyingContentMismatch() throws Exception {
    Method method =
        FileAssetMapper.class.getMethod(
            "rejectVerification", String.class, java.time.Instant.class);
    String sql = method.getAnnotation(Update.class).value()[0];

    assertTrue(sql.contains("upload_status='EXPIRED'"));
    assertTrue(sql.contains("upload_status='VERIFYING'"));
    assertTrue(sql.contains("DIRECT_STAGED_CHECKSUM_V2"));
    assertTrue(!sql.contains("upload_expires_at <="));
  }

  /** 最终墓碑只能由已建立删除闸门的记录写入，禁止绕过“删除中”状态。 */
  @Test
  void tombstoneCasRequiresDeletionGate() throws Exception {
    Method method =
        FileAssetMapper.class.getMethod(
            "completeDeletion",
            String.class,
            String.class,
            com.calles.platform.file.domain.asset.StorageType.class,
            String.class,
            java.time.Instant.class);
    String sql = method.getAnnotation(Update.class).value()[0];

    assertTrue(sql.contains("delete_requested_at IS NOT NULL"));
    assertTrue(sql.contains("deleted_at IS NULL"));
  }
}
