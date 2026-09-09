package com.calles.platform.file.config;

import com.calles.platform.file.domain.asset.StorageType;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件服务运行参数。
 *
 * <p>配置只描述当前 MinIO 单实现和本地资源预算；它不授予外网直传、跨服务读取或公开文件权限。
 */
@ConfigurationProperties(prefix = "file")
public class FileStorageProperties {
  /** 新文件默认存储类型，启动时必须有相应适配器。 */
  private Storage storage = new Storage();

  /** 应用层允许的最大实际文件字节数，默认 20 MiB。 */
  private long maxSize = 20L * 1024 * 1024;

  /** PUT 预签名有效期，必须短于上传确认总期限。 */
  private Duration putTtl = Duration.ofMinutes(5);

  /** PENDING 文件可确认的总期限。 */
  private Duration uploadTtl = Duration.ofMinutes(15);

  /** 下载预签名有效期，作为短期持有者凭证。 */
  private Duration getTtl = Duration.ofMinutes(2);

  /** 流式校验缓冲区大小，避免整文件载入内存。 */
  private int streamBufferSize = 64 * 1024;

  /** 确认任务的有界执行器参数。 */
  private Confirm confirm = new Confirm();

  /** V2 暂存直传开关；默认关闭，完成真实存储 POC 后再灰度开启。 */
  private DirectUploadV2 directUploadV2 = new DirectUploadV2();

  /** 过期扫描参数；默认关闭，避免误清共享 bucket。 */
  private Cleanup cleanup = new Cleanup();

  /**
   * @return 存储类型与 MinIO 参数的受控分组
   */
  public Storage getStorage() {
    return storage;
  }

  /**
   * @param storage 存储参数分组
   */
  public void setStorage(Storage storage) {
    this.storage = storage;
  }

  /**
   * @return 默认存储类型
   */
  public StorageType getStorageType() {
    return storage == null ? null : storage.getType();
  }

  /**
   * @return MinIO 参数
   */
  public Minio getMinio() {
    return storage == null ? null : storage.getMinio();
  }

  /**
   * @return 最大实际字节数
   */
  public long getMaxSize() {
    return maxSize;
  }

  /**
   * @param maxSize 最大实际字节数
   */
  public void setMaxSize(long maxSize) {
    this.maxSize = maxSize;
  }

  /**
   * @return PUT 签名有效期
   */
  public Duration getPutTtl() {
    return putTtl;
  }

  /**
   * @param putTtl PUT 签名有效期
   */
  public void setPutTtl(Duration putTtl) {
    this.putTtl = putTtl;
  }

  /**
   * @return 上传确认期限
   */
  public Duration getUploadTtl() {
    return uploadTtl;
  }

  /**
   * @param uploadTtl 上传确认期限
   */
  public void setUploadTtl(Duration uploadTtl) {
    this.uploadTtl = uploadTtl;
  }

  /**
   * @return GET 签名有效期
   */
  public Duration getGetTtl() {
    return getTtl;
  }

  /**
   * @param getTtl GET 签名有效期
   */
  public void setGetTtl(Duration getTtl) {
    this.getTtl = getTtl;
  }

  /**
   * @return 流式缓冲区字节数
   */
  public int getStreamBufferSize() {
    return streamBufferSize;
  }

  /**
   * @param streamBufferSize 流式缓冲区字节数
   */
  public void setStreamBufferSize(int streamBufferSize) {
    this.streamBufferSize = streamBufferSize;
  }

  /**
   * @return 确认任务参数
   */
  public Confirm getConfirm() {
    return confirm;
  }

  /**
   * @param confirm 确认任务参数
   */
  public void setConfirm(Confirm confirm) {
    this.confirm = confirm;
  }

  /**
   * @return V2 暂存直传参数
   */
  public DirectUploadV2 getDirectUploadV2() {
    return directUploadV2;
  }

  /**
   * @param directUploadV2 V2 暂存直传参数
   */
  public void setDirectUploadV2(DirectUploadV2 directUploadV2) {
    this.directUploadV2 = directUploadV2;
  }

  /**
   * @return 清理任务参数
   */
  public Cleanup getCleanup() {
    return cleanup;
  }

  /**
   * @param cleanup 清理任务参数
   */
  public void setCleanup(Cleanup cleanup) {
    this.cleanup = cleanup;
  }

  /** 启动期校验关联范围和密钥，拒绝不受控的默认回退。 */
  public void validate() {
    if (storage == null
        || storage.getType() == null
        || storage.getMinio() == null
        || !storage.getMinio().isConfigured()) {
      throw new IllegalStateException("文件存储类型、MinIO endpoint、bucket 和访问密钥必须配置");
    }
    if (maxSize < 1
        || streamBufferSize < 1024
        || confirm == null
        || cleanup == null
        || directUploadV2 == null) {
      throw new IllegalStateException("文件大小、流缓冲区和任务配置必须为正且完整");
    }
    if (putTtl == null
        || uploadTtl == null
        || getTtl == null
        || putTtl.isNegative()
        || putTtl.isZero()
        || uploadTtl.compareTo(putTtl) <= 0
        || getTtl.isNegative()
        || getTtl.isZero()) {
      throw new IllegalStateException("文件签名期限必须为正，且上传确认期限必须大于 PUT 签名期限");
    }
    storage.validate();
    confirm.validate();
    cleanup.validate();
    directUploadV2.validate(storage.getMinio());
    if (directUploadV2.isEnabled() && !cleanup.isEnabled()) {
      throw new IllegalStateException("启用 V2 暂存直传时必须同时启用清理任务");
    }
  }

  /** 存储类型与当前 MinIO 单实现参数分组，对应 file.storage.* 配置键。 */
  public static class Storage {
    /** 默认新文件使用的存储类型。 */
    private StorageType type = StorageType.MINIO;

    /** MinIO 连接、签名端点与专用 bucket 参数。 */
    private Minio minio = new Minio();

    /** SDK 建连超时，限制不可达端点占用线程。 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** SDK 单次读取超时，限制慢对象流长期占用线程。 */
    private Duration readTimeout = Duration.ofSeconds(15);

    /** SDK 调用总超时，作为确认任务预算外的传输级保护。 */
    private Duration callTimeout = Duration.ofSeconds(90);

    /**
     * @return 默认存储类型
     */
    public StorageType getType() {
      return type;
    }

    /**
     * @param type 默认存储类型
     */
    public void setType(StorageType type) {
      this.type = type;
    }

    /**
     * @return MinIO 参数
     */
    public Minio getMinio() {
      return minio;
    }

    /**
     * @param minio MinIO 参数
     */
    public void setMinio(Minio minio) {
      this.minio = minio;
    }

    /**
     * @return SDK 建连超时
     */
    public Duration getConnectTimeout() {
      return connectTimeout;
    }

    /**
     * @param connectTimeout SDK 建连超时
     */
    public void setConnectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
    }

    /**
     * @return SDK 读取超时
     */
    public Duration getReadTimeout() {
      return readTimeout;
    }

    /**
     * @param readTimeout SDK 读取超时
     */
    public void setReadTimeout(Duration readTimeout) {
      this.readTimeout = readTimeout;
    }

    /**
     * @return SDK 调用总超时
     */
    public Duration getCallTimeout() {
      return callTimeout;
    }

    /**
     * @param callTimeout SDK 调用总超时
     */
    public void setCallTimeout(Duration callTimeout) {
      this.callTimeout = callTimeout;
    }

    /** 校验超时均为有限正数。 */
    private void validate() {
      if (connectTimeout == null
          || connectTimeout.isNegative()
          || connectTimeout.isZero()
          || readTimeout == null
          || readTimeout.isNegative()
          || readTimeout.isZero()
          || callTimeout == null
          || callTimeout.isNegative()
          || callTimeout.isZero()) throw new IllegalStateException("对象存储超时必须为正");
    }
  }

  /** MinIO 的运行与客户端可达端点配置。 */
  public static class Minio {
    /** SDK 管理、读取和删除对象时连接的端点。 */
    private String endpoint = "";

    /** 客户端在签名 URL 中访问的端点，生产环境必须为受控 HTTPS 域名。 */
    private String presignEndpoint = "";

    /** 文件服务专用私有 bucket，运行请求不自动创建。 */
    private String bucket = "";

    /** 最小权限访问账号，禁止使用 root 账号。 */
    private String accessKey = "";

    /** 最小权限账号密钥，禁止写入日志或示例。 */
    private String secretKey = "";

    /** V2 客户端 PUT 允许写入的暂存对象前缀。 */
    private String stagingPrefix = "staging";

    /** V2 确认后读取的永久对象前缀。 */
    private String permanentPrefix = "permanent";

    /**
     * @return SDK 端点
     */
    public String getEndpoint() {
      return endpoint;
    }

    /**
     * @param endpoint SDK 端点
     */
    public void setEndpoint(String endpoint) {
      this.endpoint = clean(endpoint);
    }

    /**
     * @return 预签名端点
     */
    public String getPresignEndpoint() {
      return presignEndpoint;
    }

    /**
     * @param presignEndpoint 预签名端点
     */
    public void setPresignEndpoint(String presignEndpoint) {
      this.presignEndpoint = clean(presignEndpoint);
    }

    /**
     * @return 私有 bucket
     */
    public String getBucket() {
      return bucket;
    }

    /**
     * @param bucket 私有 bucket
     */
    public void setBucket(String bucket) {
      this.bucket = clean(bucket);
    }

    /**
     * @return 访问账号
     */
    public String getAccessKey() {
      return accessKey;
    }

    /**
     * @param accessKey 访问账号
     */
    public void setAccessKey(String accessKey) {
      this.accessKey = clean(accessKey);
    }

    /**
     * @return 访问密钥
     */
    public String getSecretKey() {
      return secretKey;
    }

    /**
     * @param secretKey 访问密钥
     */
    public void setSecretKey(String secretKey) {
      this.secretKey = clean(secretKey);
    }

    /**
     * @return V2 暂存对象前缀
     */
    public String getStagingPrefix() {
      return stagingPrefix;
    }

    /**
     * @param stagingPrefix V2 暂存对象前缀
     */
    public void setStagingPrefix(String stagingPrefix) {
      this.stagingPrefix = clean(stagingPrefix);
    }

    /**
     * @return V2 永久对象前缀
     */
    public String getPermanentPrefix() {
      return permanentPrefix;
    }

    /**
     * @param permanentPrefix V2 永久对象前缀
     */
    public void setPermanentPrefix(String permanentPrefix) {
      this.permanentPrefix = clean(permanentPrefix);
    }

    /**
     * @return 所有必需字段是否已注入
     */
    private boolean isConfigured() {
      return !endpoint.isBlank()
          && !presignEndpoint.isBlank()
          && !bucket.isBlank()
          && !accessKey.isBlank()
          && !secretKey.isBlank();
    }

    /** 校验 V2 前缀必须互斥且不能形成嵌套，避免 staging 写入 permanent 区域。 */
    private void validatePrefixes() {
      if (stagingPrefix.isBlank()
          || permanentPrefix.isBlank()
          || stagingPrefix.startsWith("/")
          || permanentPrefix.startsWith("/")
          || stagingPrefix.equals(permanentPrefix)
          || stagingPrefix.startsWith(permanentPrefix + "/")
          || permanentPrefix.startsWith(stagingPrefix + "/")) {
        throw new IllegalStateException("MinIO staging-prefix 和 permanent-prefix 必须为互斥的非空前缀");
      }
    }
  }

  /** V2 暂存 checksum 直传配置。 */
  public static class DirectUploadV2 {
    /** 默认关闭，必须先完成真实 MinIO/OSS checksum POC。 */
    private boolean enabled;

    /**
     * @return 是否启用 V2
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * @param enabled 是否启用 V2
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /** 校验 V2 对象前缀。 */
    private void validate(Minio minio) {
      minio.validatePrefixes();
    }
  }

  /** 有界确认执行器配置。 */
  public static class Confirm {
    /** 固定工作线程数。 */
    private int threads = 2;

    /** 拒绝前可排队的任务数。 */
    private int queueCapacity = 16;

    /** 单个确认任务的总预算；底层 I/O 超时仍需由 SDK 联调确认。 */
    private Duration taskTimeout = Duration.ofSeconds(120);

    /**
     * @return 工作线程数
     */
    public int getThreads() {
      return threads;
    }

    /**
     * @param threads 工作线程数
     */
    public void setThreads(int threads) {
      this.threads = threads;
    }

    /**
     * @return 队列容量
     */
    public int getQueueCapacity() {
      return queueCapacity;
    }

    /**
     * @param queueCapacity 队列容量
     */
    public void setQueueCapacity(int queueCapacity) {
      this.queueCapacity = queueCapacity;
    }

    /**
     * @return 任务总预算
     */
    public Duration getTaskTimeout() {
      return taskTimeout;
    }

    /**
     * @param taskTimeout 任务总预算
     */
    public void setTaskTimeout(Duration taskTimeout) {
      this.taskTimeout = taskTimeout;
    }

    /** 校验固定池、队列和任务期限。 */
    private void validate() {
      if (threads < 1
          || queueCapacity < 1
          || taskTimeout == null
          || taskTimeout.isZero()
          || taskTimeout.isNegative()) throw new IllegalStateException("确认线程、队列和任务期限必须为正");
    }
  }

  /** 定时清理配置。 */
  public static class Cleanup {
    /** 是否显式启用清理；默认关闭。 */
    private boolean enabled;

    /** 两次扫描间隔。 */
    private Duration interval = Duration.ofSeconds(60);

    /** 单批最多扫描的记录数。 */
    private int batchSize = 100;

    /** 单轮最多处理批次数，限制远端 I/O。 */
    private int maxBatches = 5;

    /** 单次调度可占用的总时长，超过后留给下一轮继续。 */
    private Duration runTimeout = Duration.ofSeconds(30);

    /**
     * @return 是否启用
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * @return 扫描间隔
     */
    public Duration getInterval() {
      return interval;
    }

    /**
     * @param interval 扫描间隔
     */
    public void setInterval(Duration interval) {
      this.interval = interval;
    }

    /**
     * @return 批次大小
     */
    public int getBatchSize() {
      return batchSize;
    }

    /**
     * @param batchSize 批次大小
     */
    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    /**
     * @return 每轮批次数上限
     */
    public int getMaxBatches() {
      return maxBatches;
    }

    /**
     * @param maxBatches 每轮批次数上限
     */
    public void setMaxBatches(int maxBatches) {
      this.maxBatches = maxBatches;
    }

    /**
     * @return 单轮总执行期限
     */
    public Duration getRunTimeout() {
      return runTimeout;
    }

    /**
     * @param runTimeout 单轮总执行期限
     */
    public void setRunTimeout(Duration runTimeout) {
      this.runTimeout = runTimeout;
    }

    /** 校验清理预算，避免启用后无界扫描。 */
    private void validate() {
      if (interval == null
          || interval.isNegative()
          || interval.isZero()
          || batchSize < 1
          || maxBatches < 1
          || runTimeout == null
          || runTimeout.isNegative()
          || runTimeout.isZero()) throw new IllegalStateException("清理间隔、批次大小、批次数和运行期限必须为正");
    }
  }

  /** 将可能为空的配置安全归一化，具体完整性由启动校验负责。 */
  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
