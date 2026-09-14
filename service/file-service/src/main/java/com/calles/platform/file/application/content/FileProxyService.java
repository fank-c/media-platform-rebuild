package com.calles.platform.file.application.content;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.application.security.AssetTokenService;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.AssetStatus;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.UploadStatus;
import com.calles.platform.file.exception.FileOperationException;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import java.io.InputStream;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 轻量静态资源（图片/头像/缩略图）受控流代理服务。
 *
 * <p>职责：负责受控公开静态资源的元数据核验、尺寸熔断保护、HTTP 304 缓存协商比对以及从底层对象存储中转数据流。
 *
 * <p>所属边界：file-service 内部应用服务层。
 * 主要协作对象：{@link FileAssetRepository}、{@link StorageFactory}、{@link FileStorageProperties}、{@link AssetTokenService}。
 * 不应承担工作：不处理 HTTP 协议序列化细节，不直接操作网关。
 */
@Service
public class FileProxyService {

  /** 文件元数据仓储端口。 */
  private final FileAssetRepository repository;

  /** 对象存储适配器工厂。 */
  private final StorageFactory storageFactory;

  /** 文件服务运行配置。 */
  private final FileStorageProperties properties;

  /** 防盗链签名令牌服务。 */
  private final AssetTokenService tokenService;

  /**
   * 构造受控流代理服务。
   *
   * @param repository 文件元数据仓储
   * @param storageFactory 存储适配器工厂
   * @param properties 文件运行配置
   * @param tokenService 签名令牌服务
   */
  public FileProxyService(
      FileAssetRepository repository,
      StorageFactory storageFactory,
      FileStorageProperties properties,
      AssetTokenService tokenService) {
    this.repository = repository;
    this.storageFactory = storageFactory;
    this.properties = properties;
    this.tokenService = tokenService;
  }

  /**
   * 受控代理读取静态资源，优先执行防盗链验签、状态校验、尺寸熔断与 304 缓存协商。
   *
   * @param fileId 文件 ID
   * @param expires 签名声明的过期时间戳
   * @param sign 客户端提交的防篡改签名
   * @param ifNoneMatch 客户端请求头中的 If-None-Match ETag 标识（可为空）
   * @return 代理结果封装，调用方若获取到非 304 数据流则必须负责关闭底层输入流
   * @throws FileOperationException 校验不通过、文件不存在或超出代理大小上限时抛出业务异常
   */
  public ProxyResult proxy(String fileId, long expires, String sign, String ifNoneMatch) {
    // 步骤 1：防盗链与时效验签，未通过抛出 403
    tokenService.verify(fileId, expires, sign);

    // 步骤 2：查询未进入删除流程的文件元数据，隐藏未知与已删除记录
    FileAsset asset = repository
        .findVisibleById(fileId)
        .orElseThrow(() -> new FileOperationException(HttpStatus.NOT_FOUND, "文件不存在或已被删除"));

    // 步骤 3：状态校验，仅允许活跃且已明确完成上传的文件
    if (asset.getStatus() != AssetStatus.ACTIVE
        || asset.getUploadStatus() != UploadStatus.COMPLETED) {
      throw new FileOperationException(HttpStatus.CONFLICT, "文件未就绪，暂不可访问");
    }

    // 步骤 4：轻量资源尺寸熔断保护（防止误请求大文件吃满微服务带宽）
    long maxSize = properties.getProxy().getMaxSize();
    if (asset.getSize() > maxSize) {
      throw new FileOperationException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "资源尺寸(" + asset.getSize() + " 字节)超过代理上限(" + maxSize + " 字节)，请通过下载链接获取");
    }

    // 步骤 5：强 ETag 构造（以文件不可变 sha256 作为强实体标识）
    String etag = "\"" + asset.getSha256() + "\"";
    Duration cacheMaxAge = properties.getProxy().getCacheMaxAge();

    // 步骤 6：HTTP 304 协商缓存比对（若命中则立即返回，不打开存储流，零带宽消耗）
    if (isEtagMatch(ifNoneMatch, asset.getSha256())) {
      return ProxyResult.notModified(etag, cacheMaxAge);
    }

    // 步骤 7：304 未命中，打开底层存储对象输入流
    ObjectStorageClient storageClient = storageFactory.require(asset.getStorageType());
    InputStream stream = storageClient.open(asset.getStorageKey());
    return ProxyResult.ok(asset, stream, etag, cacheMaxAge);
  }

  /**
   * 规范化比对 If-None-Match 与实体 sha256 摘要。
   *
   * @param ifNoneMatch 客户端请求头
   * @param sha256 实体真实不可变摘要
   * @return 是否命中缓存
   */
  private boolean isEtagMatch(String ifNoneMatch, String sha256) {
    if (ifNoneMatch == null || ifNoneMatch.trim().isEmpty() || sha256 == null) {
      return false;
    }
    String clientTag = ifNoneMatch.trim();
    // 兼容弱 ETag 前缀 W/
    if (clientTag.startsWith("W/")) {
      clientTag = clientTag.substring(2);
    }
    // 去除外层双引号后进行恒定比对
    if (clientTag.startsWith("\"") && clientTag.endsWith("\"") && clientTag.length() >= 2) {
      clientTag = clientTag.substring(1, clientTag.length() - 1);
    }
    return sha256.equalsIgnoreCase(clientTag);
  }

  /**
   * 静态资源受控代理结果。
   */
  public static class ProxyResult {
    /** 是否命中 304 Not Modified。 */
    private final boolean notModified;
    /** HTTP ETag 响应头。 */
    private final String etag;
    /** 缓存最大有效时长。 */
    private final Duration cacheMaxAge;
    /** 文件展示名。 */
    private final String filename;
    /** 内容 MIME 类型。 */
    private final String mime;
    /** 文件实际字节数。 */
    private final long size;
    /** 数据输入流，调用方负责安全关闭。 */
    private final InputStream inputStream;

    /** 命中 304 静态构造方法。 */
    public static ProxyResult notModified(String etag, Duration cacheMaxAge) {
      return new ProxyResult(true, etag, cacheMaxAge, null, null, 0, null);
    }

    /** 完整读取 200 静态构造方法。 */
    public static ProxyResult ok(
        FileAsset asset, InputStream inputStream, String etag, Duration cacheMaxAge) {
      return new ProxyResult(
          false,
          etag,
          cacheMaxAge,
          asset.getOriginName(),
          asset.getMime(),
          asset.getSize(),
          inputStream);
    }

    private ProxyResult(
        boolean notModified,
        String etag,
        Duration cacheMaxAge,
        String filename,
        String mime,
        long size,
        InputStream inputStream) {
      this.notModified = notModified;
      this.etag = etag;
      this.cacheMaxAge = cacheMaxAge;
      this.filename = filename;
      this.mime = mime != null && !mime.trim().isEmpty() ? mime : "application/octet-stream";
      this.size = size;
      this.inputStream = inputStream;
    }

    public boolean isNotModified() {
      return notModified;
    }

    public String getEtag() {
      return etag;
    }

    public Duration getCacheMaxAge() {
      return cacheMaxAge;
    }

    public String getFilename() {
      return filename;
    }

    public String getMime() {
      return mime;
    }

    public long getSize() {
      return size;
    }

    public InputStream getInputStream() {
      return inputStream;
    }
  }
}
