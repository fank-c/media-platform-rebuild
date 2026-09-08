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
 * <p>所有状态转换带条件并由调用方检查更新行数；不使用通用 updateById 绕开并发边界。
 */
@Mapper
public interface FileAssetMapper {
  /** 统一显式列顺序，避免 SQL 读取时遗漏状态字段。 */
  String COLUMNS =
      "id,origin_name,mime,declared_size,size,storage_key,storage_type,sha256,status,upload_status,"
          + "upload_expires_at,create_by,update_by,create_at,update_at,deleted_at";

  /**
   * @param asset 新元数据
   * @return 插入行数
   */
  @Insert(
      "INSERT INTO file_asset("
          + COLUMNS
          + ") VALUES(#{id},#{originName},#{mime},#{declaredSize},#{size},"
          + "#{storageKey},#{storageType},#{sha256},#{status},#{uploadStatus},#{uploadExpiresAt},#{createBy},"
          + "#{updateBy},#{createAt},#{updateAt},#{deletedAt})")
  int insert(FileAsset asset);

  /** 查询未逻辑删除且属于调用者的记录，避免向外泄露他人存在性。 */
  @Select(
      "SELECT "
          + COLUMNS
          + " FROM file_asset WHERE id=#{id} AND create_by=#{owner} AND deleted_at IS NULL LIMIT 1")
  FileAsset selectVisibleByIdAndOwner(@Param("id") String id, @Param("owner") String owner);

  /** 查询包含逻辑墓碑的本人记录，仅用于 DELETE 幂等判断，禁止直接返回 HTTP。 */
  @Select("SELECT " + COLUMNS + " FROM file_asset WHERE id=#{id} AND create_by=#{owner} LIMIT 1")
  FileAsset selectPhysicalByIdAndOwner(@Param("id") String id, @Param("owner") String owner);

  /** 读取 cutoff 前仍 PENDING 的 keyset 扫描批次。 */
  @Select(
      "<script>SELECT "
          + COLUMNS
          + " FROM file_asset WHERE deleted_at IS NULL AND upload_status='PENDING' AND"
          + " upload_expires_at &lt;= #{cutoff} <if test='cursorTime != null' AND"
          + " (upload_expires_at &gt; #{cursorTime} OR (upload_expires_at = #{cursorTime} AND id"
          + " &gt; #{cursorId})) </if> ORDER BY upload_expires_at,id LIMIT #{limit}</script>")
  List<FileAsset> selectPendingExpired(
      @Param("cutoff") Instant cutoff,
      @Param("cursorTime") Instant cursorTime,
      @Param("cursorId") String cursorId,
      @Param("limit") int limit);

  /** 用稳定 ID 游标扫描 EXPIRED 残留对象，避免单条连续失败阻塞全表。 */
  @Select(
      "SELECT "
          + COLUMNS
          + " FROM file_asset WHERE deleted_at IS NULL AND upload_status='EXPIRED' "
          + "AND (#{cursorId} IS NULL OR id > #{cursorId}) ORDER BY id LIMIT #{limit}")
  List<FileAsset> selectExpired(@Param("cursorId") String cursorId, @Param("limit") int limit);

  /** 完成确认 CAS，读取期过期、删除或状态变化时返回 0，禁止复活记录。 */
  @Update(
      "UPDATE file_asset SET"
          + " upload_status='COMPLETED',size=#{actualSize},sha256=#{sha256},update_by=#{owner},update_at=#{now}"
          + " WHERE id=#{id} AND create_by=#{owner} AND status='ACTIVE' AND deleted_at IS NULL AND"
          + " upload_status='PENDING' AND upload_expires_at > #{now} AND"
          + " declared_size=#{expectedSize} AND #{actualSize}=#{expectedSize} AND"
          + " storage_type=#{storageType} AND storage_key=#{storageKey}")
  int complete(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("expectedSize") long expectedSize,
      @Param("actualSize") long actualSize,
      @Param("sha256") String sha256,
      @Param("storageType") StorageType storageType,
      @Param("storageKey") String storageKey,
      @Param("now") Instant now);

  /** 条件抢占 PENDING 为 EXPIRED，使后续确认 CAS 不再成功。 */
  @Update(
      "UPDATE file_asset SET upload_status='EXPIRED',update_at=#{now} WHERE id=#{id} AND deleted_at"
          + " IS NULL AND upload_status='PENDING' AND upload_expires_at <= #{now}")
  int expirePending(@Param("id") String id, @Param("now") Instant now);

  /** 远端删除明确完成后写入逻辑墓碑；0 行必须由上层重新读取解释。 */
  @Update(
      "UPDATE file_asset SET deleted_at=#{now},update_by=#{owner},update_at=#{now} WHERE id=#{id}"
          + " AND create_by=#{owner} AND deleted_at IS NULL AND storage_type=#{storageType} AND"
          + " storage_key=#{storageKey}")
  int markDeleted(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("storageType") StorageType storageType,
      @Param("storageKey") String storageKey,
      @Param("now") Instant now);
}
