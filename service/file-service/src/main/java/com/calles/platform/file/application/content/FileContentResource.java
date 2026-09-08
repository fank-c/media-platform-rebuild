package com.calles.platform.file.application.content;

import com.calles.platform.file.domain.asset.FileAsset;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次内部内容读取句柄。
 *
 * <p>持有元数据快照和新打开的对象流；close 幂等，调用方必须用 try-with-resources 释放底层连接。
 */
public final class FileContentResource implements AutoCloseable {
  /** 已完成且已鉴权的元数据快照。 */
  private final FileAsset asset;

  /** 此句柄独占的对象输入流。 */
  private final InputStream input;

  /** 防止重复 close 触发底层响应异常。 */
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * @param asset 已验证的元数据快照
   * @param input 刚打开的对象流
   */
  public FileContentResource(FileAsset asset, InputStream input) {
    this.asset = asset;
    this.input = input;
  }

  /** @return 元数据快照，调用方不得用它反推内部对象 key */
  public FileAsset asset() {
    return asset;
  }

  /** @return 此次打开的流，不支持 reset；需要重读必须重新调用服务 */
  public InputStream input() {
    return input;
  }

  /** 幂等关闭对象流，I/O 异常交由调用者的资源清理路径处理。 */
  @Override
  public void close() throws java.io.IOException {
    if (closed.compareAndSet(false, true)) input.close();
  }
}
