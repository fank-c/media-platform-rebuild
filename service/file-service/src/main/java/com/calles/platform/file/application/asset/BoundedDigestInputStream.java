package com.calles.platform.file.application.asset;

import com.calles.platform.file.exception.FileOperationException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.http.HttpStatus;

/**
 * 读取时累计 SHA-256 与字节数的上限流包装器。
 *
 * <p>它不缓存整个文件；一旦超过限制立即终止读取，以免存储适配器继续消耗不受控资源。
 */
public class BoundedDigestInputStream extends FilterInputStream {
  /** SHA-256 累加器。 */
  private final MessageDigest digest;

  /** 允许的最大实际字节数。 */
  private final long maxBytes;

  /** 已交给调用方的实际字节数。 */
  private long count;

  /**
   * @param input 原始单次使用流
   * @param maxBytes 最大实际字节数
   */
  public BoundedDigestInputStream(InputStream input, long maxBytes) {
    super(input);
    this.maxBytes = maxBytes;
    try {
      this.digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM 缺少 SHA-256", exception);
    }
  }

  /** 读取单字节并更新摘要。 */
  @Override
  public int read() throws IOException {
    int value = super.read();
    if (value >= 0) {
      update(new byte[] {(byte) value}, 0, 1);
    }
    return value;
  }

  /** 批量读取并更新摘要，避免调用方必须自行重复实现计数逻辑。 */
  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    int read = super.read(bytes, offset, length);
    if (read > 0) {
      update(bytes, offset, read);
    }
    return read;
  }

  /** @return 已读取的实际字节数 */
  public long count() {
    return count;
  }

  /** @return 当前流消费结果的低写十六进制 SHA-256 */
  public String sha256Hex() {
    byte[] bytes = digest.digest();
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      hex.append(String.format("%02x", value));
    }
    return hex.toString();
  }

  /** 统一更新摘要并在超额时抛出真实 413。 */
  private void update(byte[] bytes, int offset, int length) {
    count += length;
    if (count > maxBytes) {
      throw new FileOperationException(HttpStatus.PAYLOAD_TOO_LARGE, "文件实际大小超过允许上限");
    }
    digest.update(bytes, offset, length);
  }
}
