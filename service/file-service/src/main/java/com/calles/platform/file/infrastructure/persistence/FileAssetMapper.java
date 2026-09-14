package com.calles.platform.file.infrastructure.persistence;

import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.StorageType;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * file_asset 的生命周期专用 SQL。
 *
 * <p>所有状态转换带条件并由调用方检查更新行数；不使用通用 updateById
 * 绕开上传、确认、删除和清理边界。
 */
@Mapper
public interface FileAssetMapper {
  /** 统一显式列顺序，避免 SQL 读取时遗漏 V2 状态和清理字段。 */
  String COLUMNS =
      "id,origin_name,mime,declared_size,size,storage_key,storage_type,sha256,status,upload_status,"
          + "upload_expires_at,create_by,update_by,create_at,update_at,delete_requested_at,deleted_at,"
          + "upload_protocol,staging_storage_key,expected_sha256,verification_requested_at,"
          + "verified_source_etag,staging_cleaned_at";

  /**
   * @return 插入行数
   */
  @Insert(
      "INSERT INTO file_asset("
          + COLUMNS
          + ") VALUES(#{id},#{originName},#{mime},#{declaredSize},#{size},#{storageKey},#{storageType},"
          + "#{sha256},#{status},#{uploadStatus},#{uploadExpiresAt},#{createBy},#{updateBy},#{createAt},"
          + "#{updateAt},#{deleteRequestedAt},#{deletedAt},#{uploadProtocol},#{stagingStorageKey},"
          + "#{expectedSha256},#{verificationRequestedAt},#{verifiedSourceEtag},#{stagingCleanedAt})")
  int insert(FileAsset asset);

  /** 查询未进入删除流程且属于调用者的记录，删除中和已删除文件对外不可见。 */
  @Select(
      "SELECT "
          + COLUMNS
          + " FROM file_asset WHERE id=#{id} AND create_by=#{owner} AND delete_requested_at IS NULL"
          + " AND deleted_at IS NULL LIMIT 1")
  FileAsset selectVisibleByIdAndOwner(@Param("id") String id, @Param("owner") String owner);

  /** 查询包含墓碑的本人记录，仅用于重复 DELETE 的幂等判断，禁止直接返回 HTTP。 */
  @Select("SELECT " + COLUMNS + " FROM file_asset WHERE id=#{id} AND create_by=#{owner} LIMIT 1")
  FileAsset selectPhysicalByIdAndOwner(@Param("id") String id, @Param("owner") String owner);

  /** 查询未进入删除流程的文件（不限所有者，供通过签名验签后的受控资源代理访问）。 */
  @Select(
      "SELECT "
          + COLUMNS
          + " FROM file_asset WHERE id=#{id} AND delete_requested_at IS NULL"
          + " AND deleted_at IS NULL LIMIT 1")
  FileAsset selectVisibleById(@Param("id") String id);

  /** 按确认期限扫描 PENDING/VERIFYING，避免确认中记录被普通清理遗漏。 */
  @Select(
      "<script>SELECT "
          + COLUMNS
          + " FROM file_asset WHERE delete_requested_at IS NULL AND deleted_at IS NULL"
          + " AND upload_status IN ('PENDING','VERIFYING') AND upload_expires_at &lt;= #{cutoff}"
          + " <if test='cursorTime != null'> AND"
          + " (upload_expires_at &gt; #{cursorTime} OR (upload_expires_at = #{cursorTime} AND id"
          + " &gt; #{cursorId})) </if> ORDER BY upload_expires_at,id LIMIT #{limit}</script>")
  List<FileAsset> selectPendingExpired(
      @Param("cutoff") Instant cutoff,
      @Param("cursorTime") Instant cursorTime,
      @Param("cursorId") String cursorId,
      @Param("limit") int limit);

  /** 用稳定 ID 游标扫描 EXPIRED 残留对象，不包含已建立删除闸门的文件。 */
  @Select(
      "SELECT "
          + COLUMNS
          + " FROM file_asset WHERE delete_requested_at IS NULL AND deleted_at IS NULL"
          + " AND upload_status='EXPIRED' AND (#{cursorId} IS NULL OR id > #{cursorId})"
          + " ORDER BY id LIMIT #{limit}")
  List<FileAsset> selectExpired(@Param("cursorId") String cursorId, @Param("limit") int limit);

  /** 扫描服务重启或远端异常后仍处于 VERIFYING 的 V2 记录。 */
  @Select(
      "<script>SELECT "
          + COLUMNS
          + " FROM file_asset WHERE upload_protocol='DIRECT_STAGED_CHECKSUM_V2'"
          + " AND upload_status='VERIFYING' AND delete_requested_at IS NULL AND deleted_at IS NULL"
          + " <if test='cursorTime != null'> AND (verification_requested_at &gt; #{cursorTime}"
          + " OR (verification_requested_at = #{cursorTime} AND id &gt; #{cursorId})) </if>"
          + " ORDER BY verification_requested_at,id LIMIT #{limit}</script>")
  List<FileAsset> selectVerificationRecovery(
      @Param("cursorTime") Instant cursorTime,
      @Param("cursorId") String cursorId,
      @Param("limit") int limit);

  /** 扫描已完成但 staging 删除尚未确认的 V2 记录，禁止扫描整个 bucket。 */
  @Select(
      "SELECT "
          + COLUMNS
          + " FROM file_asset WHERE upload_protocol='DIRECT_STAGED_CHECKSUM_V2'"
          + " AND upload_status='COMPLETED' AND staging_storage_key IS NOT NULL"
          + " AND staging_cleaned_at IS NULL AND delete_requested_at IS NULL AND deleted_at IS NULL"
          + " AND (#{cursorId} IS NULL OR id > #{cursorId}) ORDER BY id LIMIT #{limit}")
  List<FileAsset> selectStagingCleanup(
      @Param("cursorId") String cursorId, @Param("limit") int limit);

  /** 兼容 V1 完成 CAS；新 V2 记录不能进入此路径。 */
  @Update(
      "UPDATE file_asset SET upload_status='COMPLETED',size=#{actualSize},sha256=#{sha256},"
          + "update_by=#{owner},update_at=#{now} WHERE id=#{id} AND create_by=#{owner}"
          + " AND status='ACTIVE' AND delete_requested_at IS NULL AND deleted_at IS NULL"
          + " AND upload_protocol='LEGACY_V1' AND upload_status='PENDING'"
          + " AND upload_expires_at > #{now} AND declared_size=#{expectedSize}"
          + " AND #{actualSize}=#{expectedSize} AND storage_type=#{storageType}"
          + " AND storage_key=#{storageKey}")
  int complete(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("expectedSize") long expectedSize,
      @Param("actualSize") long actualSize,
      @Param("sha256") String sha256,
      @Param("storageType") StorageType storageType,
      @Param("storageKey") String storageKey,
      @Param("now") Instant now);

  /** PENDING -> VERIFYING 的确认闸门；删除、过期和第二次确认都不能抢占同一行。 */
  @Update(
      "UPDATE file_asset SET upload_status='VERIFYING',verification_requested_at=#{now},"
          + "update_by=#{owner},update_at=#{now} WHERE id=#{id} AND create_by=#{owner}"
          + " AND status='ACTIVE' AND delete_requested_at IS NULL AND deleted_at IS NULL"
          + " AND upload_protocol='DIRECT_STAGED_CHECKSUM_V2' AND upload_status='PENDING'"
          + " AND upload_expires_at > #{now} AND staging_storage_key IS NOT NULL"
          + " AND expected_sha256 IS NOT NULL")
  int claimVerification(
      @Param("id") String id, @Param("owner") String owner, @Param("now") Instant now);

  /** 记录 HEAD 观察到的 ETag，仍要求记录处于未删除的 VERIFYING 状态。 */
  @Update(
      "UPDATE file_asset SET verified_source_etag=#{etag},update_at=#{now} WHERE id=#{id}"
          + " AND create_by=#{owner} AND status='ACTIVE' AND delete_requested_at IS NULL"
          + " AND deleted_at IS NULL AND upload_protocol='DIRECT_STAGED_CHECKSUM_V2'"
          + " AND upload_status='VERIFYING'")
  int observeVerification(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("etag") String etag,
      @Param("now") Instant now);

  /** VERIFYING -> COMPLETED 的完成 CAS，只允许写入数据库中已绑定的 expected_sha256。 */
  @Update(
      "UPDATE file_asset SET"
          + " upload_status='COMPLETED',size=#{actualSize},sha256=expected_sha256,"
          + "verified_source_etag=#{etag},update_by=#{owner},update_at=#{now}"
          + " WHERE id=#{id} AND create_by=#{owner} AND status='ACTIVE' AND delete_requested_at IS"
          + " NULL AND deleted_at IS NULL AND upload_protocol='DIRECT_STAGED_CHECKSUM_V2' AND"
          + " upload_status='VERIFYING' AND upload_expires_at > #{now} AND"
          + " declared_size=#{expectedSize} AND #{actualSize}=#{expectedSize} AND"
          + " expected_sha256=#{expectedSha256} AND staging_storage_key=#{stagingKey} AND"
          + " storage_type=#{storageType} AND storage_key=#{storageKey} AND"
          + " verified_source_etag=#{etag}")
  int completeVerification(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("expectedSize") long expectedSize,
      @Param("actualSize") long actualSize,
      @Param("expectedSha256") String expectedSha256,
      @Param("etag") String etag,
      @Param("storageType") StorageType storageType,
      @Param("storageKey") String storageKey,
      @Param("stagingKey") String stagingKey,
      @Param("now") Instant now);

  /** 条件将未完成记录标记为 EXPIRED，使后续确认 CAS 不再成功。 */
  @Update(
      "UPDATE file_asset SET upload_status='EXPIRED',update_at=#{now} WHERE id=#{id}"
          + " AND delete_requested_at IS NULL AND deleted_at IS NULL"
          + " AND upload_status IN ('PENDING','VERIFYING') AND upload_expires_at <= #{now}")
  int expirePending(@Param("id") String id, @Param("now") Instant now);

  /** V2 已证明对象内容不符合声明时立即作废，避免无效记录持续可重试。 */
  @Update(
      "UPDATE file_asset SET upload_status='EXPIRED',update_at=#{now} WHERE id=#{id}"
          + " AND delete_requested_at IS NULL AND deleted_at IS NULL"
          + " AND upload_protocol='DIRECT_STAGED_CHECKSUM_V2' AND upload_status='VERIFYING'")
  int rejectVerification(@Param("id") String id, @Param("now") Instant now);

  /** V1 已确认内容不一致时立即作废，只允许 LEGACY_V1/PENDING，技术失败仍可重试。 */
  @Update(
      "UPDATE file_asset SET upload_status='EXPIRED',update_at=#{now} WHERE id=#{id}"
          + " AND delete_requested_at IS NULL AND deleted_at IS NULL"
          + " AND upload_protocol='LEGACY_V1' AND upload_status='PENDING'")
  int rejectPendingContent(@Param("id") String id, @Param("now") Instant now);

  /** 完成 V2 暂存对象删除后记录清理时间，不改变 COMPLETED 语义。 */
  @Update(
      "UPDATE file_asset SET staging_cleaned_at=#{now},update_at=#{now} WHERE id=#{id}"
          + " AND upload_protocol='DIRECT_STAGED_CHECKSUM_V2' AND upload_status='COMPLETED'"
          + " AND staging_storage_key=#{stagingKey} AND staging_cleaned_at IS NULL"
          + " AND delete_requested_at IS NULL AND deleted_at IS NULL")
  int markStagingCleaned(
      @Param("id") String id, @Param("stagingKey") String stagingKey, @Param("now") Instant now);

  /** 条件建立删除闸门；抢占成功后普通可见查询必须立即隐藏该记录。 */
  @Update(
      "UPDATE file_asset SET delete_requested_at=#{now},update_by=#{owner},update_at=#{now}"
          + " WHERE id=#{id} AND create_by=#{owner} AND delete_requested_at IS NULL"
          + " AND deleted_at IS NULL")
  int requestDeletion(
      @Param("id") String id, @Param("owner") String owner, @Param("now") Instant now);

  /** 远端两个位置都明确收敛后写入逻辑墓碑；删除闸门不存在时禁止绕过中间状态。 */
  @Update(
      "UPDATE file_asset SET deleted_at=#{now},update_by=#{owner},update_at=#{now} WHERE id=#{id}"
          + " AND create_by=#{owner} AND delete_requested_at IS NOT NULL AND deleted_at IS NULL"
          + " AND storage_type=#{storageType} AND storage_key=#{storageKey}")
  int completeDeletion(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("storageType") StorageType storageType,
      @Param("storageKey") String storageKey,
      @Param("now") Instant now);

  /** 按删除闸门建立时刻 keyset 扫描，供独立恢复任务重新删除两个远端位置并落墓碑。 */
  @Select(
      "<script>SELECT "
          + COLUMNS
          + " FROM file_asset WHERE delete_requested_at IS NOT NULL AND deleted_at IS NULL"
          + " <if test='cursorTime != null'> AND (delete_requested_at &gt; #{cursorTime}"
          + " OR (delete_requested_at = #{cursorTime} AND id &gt; #{cursorId})) </if>"
          + " ORDER BY delete_requested_at,id LIMIT #{limit}</script>")
  List<FileAsset> selectDeletionRequested(
      @Param("cursorTime") Instant cursorTime,
      @Param("cursorId") String cursorId,
      @Param("limit") int limit);
}
