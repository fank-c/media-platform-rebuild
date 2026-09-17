package com.calles.platform.file.application.asset;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.AssetStatus;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.StorageType;
import com.calles.platform.file.domain.asset.UploadProtocol;
import com.calles.platform.file.domain.asset.UploadStatus;
import com.calles.platform.file.exception.FileOperationException;
import com.calles.platform.file.exception.ObjectStorageException;
import com.calles.platform.file.infrastructure.observability.FileOperationalMetrics;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import com.calles.platform.file.interfaces.http.dto.DirectUploadRequest;
import com.calles.platform.file.interfaces.http.dto.DirectUploadV2Request;
import com.calles.platform.file.interfaces.http.dto.FileResponses;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件创建、查询、下载签名和删除用例。
 *
 * <p>普通 multipart 与旧 V1 直传保持兼容；V2 直传只向 staging 签发 checksum PUT，并把 permanent key
 * 固定在数据库中，确认时通过对象存储服务端 copy 迁移。
 */
@Service
public class FileAssetApplicationService {
  /** 对象 key 中使用的 UTC 日期格式；日期分层仅用于对象组织，不参与文件身份判断。 */
  private static final DateTimeFormatter STORAGE_KEY_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

  /** V2 客户端摘要格式：必须是 64 位小写十六进制。 */
  private static final String SHA256_PATTERN = "[0-9a-f]{64}";

  /** 元数据仓储。 */
  private final FileAssetRepository repository;

  /** 存储适配器工厂。 */
  private final StorageFactory storageFactory;

  /** 已校验的文件资源预算。 */
  private final FileStorageProperties properties;

  /** UTC 时钟。 */
  private final Clock clock;

  /** 脱敏运行指标。 */
  private final FileOperationalMetrics metrics;

  /**
   * @param repository 元数据仓储
   * @param storageFactory 存储工厂
   * @param properties 参数
   * @param clock UTC 时钟
   * @param metrics 脱敏运行指标
   */
  public FileAssetApplicationService(
      FileAssetRepository repository,
      StorageFactory storageFactory,
      FileStorageProperties properties,
      Clock clock,
      FileOperationalMetrics metrics) {
    this.repository = repository;
    this.storageFactory = storageFactory;
    this.properties = properties;
    this.clock = clock;
    this.metrics = metrics;
  }

  /**
   * 执行单文件 multipart 上传。
   *
   * @param owner 当前普通用户 ID
   * @param multipart 请求级文件；只在本方法同步生命周期内读取
   * @param requestedStorageType 可选受控存储类型
   * @return 已完成且已持久化的公开元数据
   */
  public FileResponses.Metadata upload(
      String owner, MultipartFile multipart, String requestedStorageType) {
    if (multipart == null || multipart.isEmpty()) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "文件不能为空");
    }
    long declaredSize = multipart.getSize();
    validateDeclaredSize(declaredSize);
    String name = normalizeName(multipart.getOriginalFilename());
    String mime = normalizeMime(multipart.getContentType());
    StorageType type = resolveStorageType(requestedStorageType);
    String id = newId();
    Instant now = Instant.now(clock);
    String key = permanentStorageKey(id, now);
    ObjectStorageClient client = storageFactory.require(type);
    try (InputStream source = multipart.getInputStream();
        BoundedDigestInputStream digest =
            new BoundedDigestInputStream(source, properties.getMaxSize())) {
      // 远端 I/O 不包裹数据库事务；只有明确 PUT 成功并核对实际流才写完成元数据。
      client.put(key, mime, digest, declaredSize);
      if (digest.count() != declaredSize) {
        throw new FileOperationException(HttpStatus.BAD_REQUEST, "文件实际大小与请求声明不一致");
      }
      FileAsset asset =
          completedAsset(
              id,
              name,
              mime,
              declaredSize,
              digest.sha256Hex(),
              type,
              key,
              owner,
              now,
              UploadProtocol.SERVER_MULTIPART_V1);
      if (repository.insert(asset) != 1) {
        // DB 结果未知时不盲删刚上传对象，保留给受控人工核对而不是伪造成功。
        throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "文件元数据保存未确认");
      }
      metrics.uploadSuccess();
      return FileAssetViews.metadata(asset);
    } catch (FileOperationException exception) {
      metrics.operationFailure();
      throw exception;
    } catch (IOException exception) {
      metrics.operationFailure();
      throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "读取上传文件失败", exception);
    } catch (ObjectStorageException exception) {
      metrics.operationFailure();
      throw storageFailure("上传文件失败", exception);
    }
  }

  /**
   * 创建旧 V1 PENDING 元数据并生成直接写入 legacy key 的 PUT 临时签名。
   *
   * @param owner 当前用户 ID
   * @param request 旧版直传初始化声明
   * @return PENDING 状态和短期 PUT URL
   */
  public FileResponses.DirectUpload initializeDirectUpload(
      String owner, DirectUploadRequest request) {
    if (request == null || request.size() == null) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "文件名和大小不能为空");
    }
    validateDeclaredSize(request.size());
    Instant now = Instant.now(clock);
    String id = newId();
    String key = legacyStorageKey(id, now);
    String name = normalizeName(request.originName());
    String mime = normalizeMime(request.mime());
    StorageType type = resolveStorageType(request.storageType());
    Instant uploadExpiresAt = now.plus(properties.getUploadTtl());
    FileAsset pending =
        pendingAsset(
            id,
            name,
            mime,
            request.size(),
            key,
            null,
            null,
            type,
            owner,
            now,
            uploadExpiresAt,
            UploadProtocol.LEGACY_V1);
    insertPending(pending);
    try {
      ObjectStorageClient.PresignedUrl signed =
          storageFactory.require(type).presignPut(key, mime, properties.getPutTtl());
      return directUploadResponse(id, signed, uploadExpiresAt);
    } catch (ObjectStorageException exception) {
      metrics.operationFailure();
      throw storageFailure("生成上传签名失败", exception);
    }
  }

  /**
   * 创建 V2 PENDING 元数据，并只向 staging key 签发带 checksum 的 PUT 签名。
   *
   * @param owner 当前用户 ID
   * @param request V2 直传初始化声明
   * @return 不暴露内部 key 的 PENDING 直传响应
   */
  public FileResponses.DirectUpload initializeDirectUploadV2(
      String owner, DirectUploadV2Request request) {
    requireV2Enabled();
    if (request == null || request.size() == null || request.sha256() == null) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "文件名、大小和 SHA-256 不能为空");
    }
    validateDeclaredSize(request.size());
    String expectedSha256 = normalizeSha256(request.sha256());
    Instant now = Instant.now(clock);
    String id = newId();
    String stagingKey = stagingStorageKey(id, now);
    String permanentKey = permanentStorageKey(id, now);
    String name = normalizeName(request.originName());
    String mime = normalizeMime(request.mime());
    StorageType type = resolveStorageType(request.storageType());
    Instant uploadExpiresAt = now.plus(properties.getUploadTtl());
    FileAsset pending =
        pendingAsset(
            id,
            name,
            mime,
            request.size(),
            permanentKey,
            stagingKey,
            expectedSha256,
            type,
            owner,
            now,
            uploadExpiresAt,
            UploadProtocol.DIRECT_STAGED_CHECKSUM_V2);
    insertPending(pending);
    try {
      // 只有存储适配器把 checksum header 纳入签名，客户端声明才具备对象存储校验约束。
      String checksumBase64 = Base64.getEncoder().encodeToString(hexToBytes(expectedSha256));
      ObjectStorageClient.PresignedUrl signed =
          storageFactory
              .require(type)
              .presignPut(stagingKey, mime, checksumBase64, properties.getPutTtl());
      return directUploadResponse(id, signed, uploadExpiresAt);
    } catch (ObjectStorageException exception) {
      metrics.operationFailure();
      throw storageFailure("生成上传签名失败", exception);
    }
  }

  /**
   * @return 本人可见元数据；PENDING/VERIFYING/EXPIRED 仍可查询状态
   */
  public FileResponses.Metadata get(String owner, String id) {
    return FileAssetViews.metadata(requireVisible(owner, id));
  }

  /**
   * 为本人已完成资源生成 GET 签名，并以 HEAD 大小检查显式发现已删对象或明显漂移。
   *
   * @param owner 当前用户 ID
   * @param id 文件 ID
   * @return no-store 响应中使用的短期下载 URL
   */
  public FileResponses.DownloadUrl downloadUrl(String owner, String id) {
    FileAsset asset = requireVisible(owner, id);
    if (asset.getStatus() != AssetStatus.ACTIVE
        || asset.getUploadStatus() == UploadStatus.PENDING
        || asset.getUploadStatus() == UploadStatus.VERIFYING) {
      throw new FileOperationException(HttpStatus.CONFLICT, "文件尚未完成上传");
    }
    if (asset.getUploadStatus() == UploadStatus.EXPIRED) {
      throw new FileOperationException(HttpStatus.GONE, "上传已过期");
    }
    try {
      ObjectStorageClient client = storageFactory.require(asset.getStorageType());
      ObjectStorageClient.ObjectHead head = client.head(asset.getStorageKey());
      if (asset.getSize() == null || head.size() != asset.getSize()) {
        throw new FileOperationException(HttpStatus.CONFLICT, "文件对象状态异常");
      }
      ObjectStorageClient.PresignedUrl signed =
          client.presignGet(asset.getStorageKey(), properties.getGetTtl());
      return new FileResponses.DownloadUrl(signed.url(), signed.expiresAt());
    } catch (ObjectStorageException exception) {
      throw storageFailure("文件对象暂不可下载", exception);
    }
  }

  /**
   * 内部微服务专属获取下载直链（无需用户会话，基于未删除活跃文件资产签发 GET 签名）。
   *
   * @param id 文件资产全局 ID
   * @return no-store 响应中使用的短期下载 URL
   */
  public FileResponses.DownloadUrl downloadUrlInternal(String id) {
    FileAsset asset =
        repository
            .findVisibleById(id)
            .orElseThrow(() -> new FileOperationException(HttpStatus.NOT_FOUND, "文件不存在"));
    if (asset.getStatus() != AssetStatus.ACTIVE
        || asset.getUploadStatus() == UploadStatus.PENDING
        || asset.getUploadStatus() == UploadStatus.VERIFYING) {
      throw new FileOperationException(HttpStatus.CONFLICT, "文件尚未完成上传");
    }
    if (asset.getUploadStatus() == UploadStatus.EXPIRED) {
      throw new FileOperationException(HttpStatus.GONE, "上传已过期");
    }
    try {
      ObjectStorageClient client = storageFactory.require(asset.getStorageType());
      ObjectStorageClient.PresignedUrl signed =
          client.presignGet(asset.getStorageKey(), properties.getGetTtl());
      return new FileResponses.DownloadUrl(signed.url(), signed.expiresAt());
    } catch (ObjectStorageException exception) {
      throw storageFailure("文件对象暂不可下载", exception);
    }
  }

  /**
   * 先条件建立数据库删除闸门，再删除 permanent 和 staging 远端对象并落逻辑墓碑。
   *
   * @param owner 当前用户 ID
   * @param id 文件 ID
   */
  public void delete(String owner, String id) {
    FileAsset physical = requirePhysical(owner, id);
    if (physical.getDeletedAt() != null) return;
    Instant requestedAt = Instant.now(clock);
    if (repository.requestDeletion(id, owner, requestedAt) == 1) {
      // CAS 成功是关闭确认、读取与下载竞争窗口的唯一建立点。
      metrics.deletionRequested();
    } else {
      // 未抢到时只接受已完成墓碑，或沿用已有删除闸门重试。
      // 绝不把删除中的记录重新显示给调用方。
      physical = requirePhysical(owner, id);
      if (physical.getDeletedAt() != null) return;
      if (physical.getDeleteRequestedAt() == null) {
        metrics.deletionFailure();
        throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "文件删除状态未确认");
      }
    }
    deleteRemoteAndComplete(physical, owner);
  }

  /** 删除一条记录的 permanent/staging 对象，并在明确收敛后写墓碑。 */
  private void deleteRemoteAndComplete(FileAsset asset, String owner) {
    ObjectStorageClient client = storageFactory.require(asset.getStorageType());
    deleteOne(client, asset.getStorageKey());
    if (asset.getStagingStorageKey() != null
        && !asset.getStagingStorageKey().equals(asset.getStorageKey())) {
      deleteOne(client, asset.getStagingStorageKey());
    }
    Instant completedAt = Instant.now(clock);
    if (repository.completeDeletion(
            asset.getId(), owner, asset.getStorageType(), asset.getStorageKey(), completedAt)
        == 1) {
      metrics.deletionSuccess();
      return;
    }
    FileAsset reloaded = requirePhysical(owner, asset.getId());
    if (reloaded.getDeletedAt() != null) {
      metrics.deletionSuccess();
      return;
    }
    metrics.deletionFailure();
    throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "文件删除状态未确认");
  }

  /** 删除远端对象；明确不存在视为收敛，其他异常保留删除闸门。 */
  private void deleteOne(ObjectStorageClient client, String key) {
    try {
      client.delete(key);
    } catch (ObjectStorageException exception) {
      if (exception.getCategory() != ObjectStorageException.Category.NOT_FOUND) {
        metrics.deletionFailure();
        throw storageFailure("删除文件对象未确认", exception);
      }
    }
  }

  /** 构造同步 multipart 或兼容记录的 COMPLETED 元数据。 */
  private FileAsset completedAsset(
      String id,
      String name,
      String mime,
      long size,
      String sha256,
      StorageType type,
      String key,
      String owner,
      Instant now,
      UploadProtocol protocol) {
    FileAsset asset =
        new FileAsset(
            id,
            name,
            mime,
            size,
            size,
            key,
            type,
            sha256,
            AssetStatus.ACTIVE,
            UploadStatus.COMPLETED,
            null,
            owner,
            owner,
            now,
            now,
            null,
            null);
    asset.setUploadProtocol(protocol);
    return asset;
  }

  /** 构造未完成记录，客户端摘要只进入 expectedSha256，不能提前写入 sha256。 */
  private FileAsset pendingAsset(
      String id,
      String name,
      String mime,
      long size,
      String permanentKey,
      String stagingKey,
      String expectedSha256,
      StorageType type,
      String owner,
      Instant now,
      Instant uploadExpiresAt,
      UploadProtocol protocol) {
    FileAsset asset =
        new FileAsset(
            id,
            name,
            mime,
            size,
            null,
            permanentKey,
            type,
            null,
            AssetStatus.ACTIVE,
            UploadStatus.PENDING,
            uploadExpiresAt,
            owner,
            owner,
            now,
            now,
            null,
            null);
    asset.setUploadProtocol(protocol);
    asset.setStagingStorageKey(stagingKey);
    asset.setExpectedSha256(expectedSha256);
    return asset;
  }

  /**
   * @param asset 待插入记录
   */
  private void insertPending(FileAsset asset) {
    if (repository.insert(asset) != 1) {
      throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "创建上传记录失败");
    }
  }

  /**
   * @param id 文件 ID @param signed 预签名结果 @param uploadExpiresAt 上传期限 @return HTTP DTO
   */
  private FileResponses.DirectUpload directUploadResponse(
      String id, ObjectStorageClient.PresignedUrl signed, Instant uploadExpiresAt) {
    return new FileResponses.DirectUpload(
        id,
        UploadStatus.PENDING.name(),
        signed.url(),
        signed.requiredHeaders(),
        signed.expiresAt(),
        uploadExpiresAt);
  }

  /** 将客户端输入限定为唯一已注册的存储枚举。 */
  private StorageType resolveStorageType(String requested) {
    if (requested == null || requested.isBlank()) return properties.getStorageType();
    try {
      return StorageType.valueOf(requested.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "不支持的文件存储类型");
    }
  }

  /** 拒绝空文件、非正值和超过首期应用预算的声明。 */
  private void validateDeclaredSize(long size) {
    if (size < 1) throw new FileOperationException(HttpStatus.BAD_REQUEST, "暂不支持空文件");
    if (size > properties.getMaxSize()) {
      throw new FileOperationException(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过允许大小");
    }
  }

  /** 校验 V2 摘要为小写 hex，避免把任意客户端字符串当成可信摘要。 */
  private String normalizeSha256(String value) {
    String normalized = value.trim();
    if (!normalized.matches(SHA256_PATTERN)) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "SHA-256 必须是 64 位小写十六进制");
    }
    return normalized;
  }

  /** 将 hex 摘要转换成 checksum header 所需的原始 32 字节。 */
  private byte[] hexToBytes(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return bytes;
  }

  /** 清理展示名而不把浏览器 Content-Disposition 原文或路径当作可信对象 key。 */
  private String normalizeName(String name) {
    if (name == null || name.isBlank()) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "文件名不能为空");
    }
    String cleaned = name.trim();
    if (cleaned.length() > 255
        || cleaned.indexOf('/') >= 0
        || cleaned.indexOf('\\') >= 0
        || cleaned.chars().anyMatch(Character::isISOControl)) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "文件名不合法");
    }
    return cleaned;
  }

  /** 归一化声明 MIME；该值不是病毒扫描或内容类型鉴定结果。 */
  private String normalizeMime(String mime) {
    String normalized =
        mime == null || mime.isBlank()
            ? "application/octet-stream"
            : mime.trim().toLowerCase(Locale.ROOT);
    if (normalized.length() > 127 || normalized.chars().anyMatch(Character::isISOControl)) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "文件类型不合法");
    }
    return normalized;
  }

  /** 启用 V2 前提是 checksum 存储 POC 已完成且清理恢复闭环可用。 */
  private void requireV2Enabled() {
    if (properties.getDirectUploadV2() == null || !properties.getDirectUploadV2().isEnabled()) {
      throw new FileOperationException(HttpStatus.NOT_FOUND, "V2 直传尚未启用");
    }
  }

  /**
   * @return 无连字符 UUID，长度与 file_asset CHAR(32) 一致
   */
  private String newId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  /** 旧协议和 multipart 的兼容对象前缀，既有记录不搬迁。 */
  private String legacyStorageKey(String id, Instant createdAt) {
    return "assets/" + STORAGE_KEY_DATE_FORMAT.format(createdAt) + "/" + id;
  }

  /** V2 staging key：客户端临时凭据永远只指向该前缀。 */
  private String stagingStorageKey(String id, Instant createdAt) {
    return prefixKey(properties.getMinio().getStagingPrefix(), createdAt, id);
  }

  /** V2 permanent key：完成后的 GET 和删除只使用该位置。 */
  private String permanentStorageKey(String id, Instant createdAt) {
    return prefixKey(properties.getMinio().getPermanentPrefix(), createdAt, id);
  }

  /** 使用配置前缀和 UTC 日期生成不可预测的服务端对象 key。 */
  private String prefixKey(String prefix, Instant createdAt, String id) {
    return prefix + "/" + STORAGE_KEY_DATE_FORMAT.format(createdAt) + "/" + id;
  }

  /** 隐藏对象存储内部类型，给客户端稳定的 503 或明确 409。 */
  private FileOperationException storageFailure(String message, ObjectStorageException exception) {
    if (exception.getCategory() == ObjectStorageException.Category.NOT_FOUND) {
      return new FileOperationException(HttpStatus.CONFLICT, "文件对象不存在", exception);
    }
    return new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, message, exception);
  }

  /** 查询包含删除墓碑的本人记录，仅用于删除幂等与删除恢复解释。 */
  private FileAsset requirePhysical(String owner, String id) {
    return repository
        .findPhysicalByIdAndOwner(id, owner)
        .orElseThrow(() -> new FileOperationException(HttpStatus.NOT_FOUND, "文件不存在"));
  }

  /** 隐藏未知、他人、删除中和已删除记录。 */
  private FileAsset requireVisible(String owner, String id) {
    return repository
        .findVisibleByIdAndOwner(id, owner)
        .orElseThrow(() -> new FileOperationException(HttpStatus.NOT_FOUND, "文件不存在"));
  }
}
