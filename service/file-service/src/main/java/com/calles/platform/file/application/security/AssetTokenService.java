package com.calles.platform.file.application.security;

import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.exception.FileOperationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 静态资源时效签名与防盗链令牌服务。
 *
 * <p>职责：负责生成和校验带有过期时间的 HMAC-SHA256 防盗链签名，使前端 HTML 标签（如 {@code <img>}）
 * 能够在无需携带 HTTP Authorization Bearer 请求头的情况下安全受控地访问静态资源。
 *
 * <p>主要协作对象：{@link FileStorageProperties}。
 * 不承担工作：不直接操作数据库与对象存储，不直接向 HTTP 响应写入流。
 */
@Service
public class AssetTokenService {

  /** HMAC-SHA256 算法常量。 */
  private static final String HMAC_SHA256 = "HmacSHA256";

  /** 文件运行配置，提供签名密钥与默认过期时间。 */
  private final FileStorageProperties properties;

  /**
   * 构造时序签名服务。
   *
   * @param properties 文件存储与安全配置
   */
  public AssetTokenService(FileStorageProperties properties) {
    this.properties = properties;
  }

  /**
   * 为指定文件生成带有默认有效期的安全访问相对路径。
   *
   * @param fileId 文件 ID
   * @return 带有 expires 和 sign 参数的相对资源访问路径
   */
  public String generateAssetUrl(String fileId) {
    return generateAssetUrl(fileId, properties.getSecurity().getDefaultTtl());
  }

  /**
   * 为指定文件生成带有指定有效期的安全访问相对路径。
   *
   * @param fileId 文件 ID
   * @param ttl 签名有效时长
   * @return 带有 expires 和 sign 参数的相对资源访问路径
   */
  public String generateAssetUrl(String fileId, Duration ttl) {
    if (fileId == null || fileId.trim().isEmpty()) {
      throw new IllegalArgumentException("生成资源链接时 fileId 不能为空");
    }
    Duration validTtl = (ttl == null || ttl.isNegative() || ttl.isZero())
        ? properties.getSecurity().getDefaultTtl()
        : ttl;

    // 步骤 1：计算绝对毫秒时间戳
    long expires = System.currentTimeMillis() + validTtl.toMillis();

    // 步骤 2：生成 HMAC-SHA256 防篡改签名
    String sign = calculateSignature(fileId, expires);

    // 步骤 3：拼接为标准静态资源访问路径
    return "/api/files/assets/" + fileId + "?expires=" + expires + "&sign=" + sign;
  }

  /**
   * 校验资源签名的合法性与有效期限。
   *
   * @param fileId 文件 ID
   * @param expires 签名声明的过期时间戳（毫秒）
   * @param sign 客户端提交的防篡改签名
   * @throws FileOperationException 当签名缺失、超时或比对不匹配时抛出 403 业务异常
   */
  public void verify(String fileId, long expires, String sign) {
    // 步骤 1：入参基础校验
    if (fileId == null || fileId.trim().isEmpty() || sign == null || sign.trim().isEmpty()) {
      throw new FileOperationException(HttpStatus.FORBIDDEN, "资源访问参数不完整");
    }

    // 步骤 2：过期时效性检查
    long now = System.currentTimeMillis();
    if (expires <= now) {
      throw new FileOperationException(HttpStatus.FORBIDDEN, "资源访问链接已过期");
    }

    // 步骤 3：重新计算预期签名
    String expectedSign = calculateSignature(fileId, expires);

    // 步骤 4：使用恒定时间比对，防止时序侧信道攻击
    byte[] expectedBytes = expectedSign.getBytes(StandardCharsets.UTF_8);
    byte[] actualBytes = sign.trim().getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
      throw new FileOperationException(HttpStatus.FORBIDDEN, "非法资源访问凭证");
    }
  }

  /**
   * 根据 fileId 与 expires 计算 HMAC-SHA256 签名摘要。
   *
   * @param fileId 文件 ID
   * @param expires 过期时间戳
   * @return 64 位小写十六进制签名串
   */
  public String calculateSignature(String fileId, long expires) {
    String payload = fileId.trim() + ":" + expires;
    byte[] secretBytes = properties.getSecurity().getTokenSecret().getBytes(StandardCharsets.UTF_8);
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      mac.init(new SecretKeySpec(secretBytes, HMAC_SHA256));
      byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception exception) {
      throw new IllegalStateException("计算资源防盗链签名失败", exception);
    }
  }
}
