package com.calles.platform.file.application.asset;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.AssetStatus;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.StorageType;
import com.calles.platform.file.domain.asset.UploadStatus;
import com.calles.platform.file.exception.FileOperationException;
import com.calles.platform.file.exception.ObjectStorageException;
import com.calles.platform.file.infrastructure.observability.FileOperationalMetrics;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import com.calles.platform.file.interfaces.http.dto.DirectUploadRequest;
import com.calles.platform.file.interfaces.http.dto.FileResponses;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件创建、查询、下载签名和删除用例。
 *
 * <p>同步 multipart 路径先写远端对象、后插入 COMPLETED 元数据；预签名直传才使用 PENDING 状态。
 */
@Service
public class FileAssetApplicationService {
  /** 对象 key 中使用的 UTC 日期格式；日期分层仅用于对象组织，不参与文件身份判断。 */
  private static final DateTimeFormatter STORAGE_KEY_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

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
    String key = storageKey(id, now);
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
          completedAsset(id, name, mime, declaredSize, digest.sha256Hex(), type, key, owner, now);
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
   * 创建 PENDING 元数据并生成 PUT 临时签名；签名失败留下的记录由过期清理负责收敛。
   *
   * @param owner 当前用户 ID
   * @param request 直传初始化声明
   * @return PENDING 状态和短期 PUT URL
   */
  public FileResponses.DirectUpload initializeDirectUpload(
      String owner, DirectUploadRequest request) {
    if (request == null || request.size() == null) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "文件名和大小不能为空");
    }
    validateDeclaredSize(request.size());
    String id = newId();
    Instant now = Instant.now(clock);
    String key = storageKey(id, now);
    String name = normalizeName(request.originName());
    String mime = normalizeMime(request.mime());
    StorageType type = resolveStorageType(request.storageType());
    Instant uploadExpiresAt = now.plus(properties.getUploadTtl());
    FileAsset pending =
        new FileAsset(
            id,
            name,
            mime,
            request.size(),
            null,
            key,
            type,
            null,
            AssetStatus.ACTIVE,
            UploadStatus.PENDING,
            uploadExpiresAt,
            owner,
            owner,
            now,
            now,
            null);
    if (repository.insert(pending) != 1) {
      throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "创建上传记录失败");
    }
    try {
      ObjectStorageClient.PresignedUrl signed =
          storageFactory.require(type).presignPut(key, mime, properties.getPutTtl());
      return new FileResponses.DirectUpload(
          id,
          UploadStatus.PENDING.name(),
          signed.url(),
          signed.requiredHeaders(),
          signed.expiresAt(),
          uploadExpiresAt);
    } catch (ObjectStorageException exception) {
      metrics.operationFailure();
      throw storageFailure("生成上传签名失败", exception);
    }
  }

  /**
   * @param owner 当前用户 ID
   * @param id 文件 ID
   * @return 本人可见元数据，PENDING/EXPIRED 均可被查询
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
        || asset.getUploadStatus() == UploadStatus.PENDING) {
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
   * 先删除远端对象，再写逻辑墓碑；远端结果未知时保留原记录并返回 503。
   *
   * @param owner 当前用户 ID
   * @param id 文件 ID
   */
  public void delete(String owner, String id) {
    FileAsset physical =
        repository
            .findPhysicalByIdAndOwner(id, owner)
            .orElseThrow(() -> new FileOperationException(HttpStatus.NOT_FOUND, "文件不存在"));
    if (physical.getDeletedAt() != null) {
      return;
    }
    try {
      // 固定本行 key/type，禁止读到新默认配置后误删其他存储对象。
      storageFactory.require(physical.getStorageType()).delete(physical.getStorageKey());
    } catch (ObjectStorageException exception) {
      if (exception.getCategory() != ObjectStorageException.Category.NOT_FOUND) {
        throw storageFailure("删除文件对象未确认", exception);
      }
    }
    Instant now = Instant.now(clock);
    if (repository.markDeleted(id, owner, physical.getStorageType(), physical.getStorageKey(), now)
        == 0) {
      FileAsset reloaded =
          repository
              .findPhysicalByIdAndOwner(id, owner)
              .orElseThrow(() -> new FileOperationException(HttpStatus.NOT_FOUND, "文件不存在"));
      if (reloaded.getDeletedAt() == null) {
        throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "文件删除状态未确认");
      }
    }
  }

  /** 创建同步上传的 COMPLETED 元数据。 */
  private FileAsset completedAsset(
      String id,
      String name,
      String mime,
      long size,
      String sha256,
      StorageType type,
      String key,
      String owner,
      Instant now) {
    return new FileAsset(
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
        null);
  }

  /** 将客户端输入限定为唯一已注册的存储枚举。 */
  private StorageType resolveStorageType(String requested) {
    if (requested == null || requested.isBlank()) {
      return properties.getStorageType();
    }
    try {
      return StorageType.valueOf(requested.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "不支持的文件存储类型");
    }
  }

  /** 拒绝空文件、非正值和超过首期应用预算的声明。 */
  private void validateDeclaredSize(long size) {
    if (size < 1) {
      throw new FileOperationException(HttpStatus.BAD_REQUEST, "暂不支持空文件");
    }
    if (size > properties.getMaxSize()) {
      throw new FileOperationException(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过允许大小");
    }
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

  /**
   * @return 无连字符 UUID，长度与 file_asset CHAR(32) 一致
   */
  private String newId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  /**
   * 按服务端 UTC 创建日期为新对象划分逻辑前缀，并以随机文件 ID 保持 key 不可预测且永不复用。
   *
   * <p>该 key 一经写入 file_asset 即成为后续读取、删除和确认的唯一定位依据；不会因日期或默认配置变化重新计算。
   *
   * @param id 服务端生成的文件 ID
   * @param createdAt 本次文件元数据创建时刻
   * @return 形如 {@code assets/2026/09/08/<fileId>} 的内部对象 key
   */
  private String storageKey(String id, Instant createdAt) {
    return "assets/" + STORAGE_KEY_DATE_FORMAT.format(createdAt) + "/" + id;
  }

  /** 隐藏对象存储内部类型，给客户端稳定的 503 或明确 409。 */
  private FileOperationException storageFailure(String message, ObjectStorageException exception) {
    if (exception.getCategory() == ObjectStorageException.Category.NOT_FOUND)
      return new FileOperationException(HttpStatus.CONFLICT, "文件对象不存在", exception);
    return new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, message, exception);
  }

  /** 隐藏未知、他人和已删除记录。 */
  private FileAsset requireVisible(String owner, String id) {
    return repository
        .findVisibleByIdAndOwner(id, owner)
        .orElseThrow(() -> new FileOperationException(HttpStatus.NOT_FOUND, "文件不存在"));
  }
}
