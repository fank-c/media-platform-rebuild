package com.calles.platform.file.application.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.calles.platform.file.exception.FileOperationException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 有界流式摘要工具的单元测试。 */
class BoundedDigestInputStreamTest {
  /** 读取后应得到实际字节数与确定 SHA-256，不需要整文件缓存。 */
  @Test
  void countsAndDigestsStream() throws Exception {
    try (BoundedDigestInputStream input =
        new BoundedDigestInputStream(
            new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), 3)) {
      while (input.read() != -1) {
        /* 消费流以完成摘要。 */
      }
      assertEquals(3, input.count());
      assertEquals(
          "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", input.sha256Hex());
    }
  }

  /** 超过上限时必须立即抛出 413，而不是继续将内容交给对象存储。 */
  @Test
  void rejectsOversizedActualStream() {
    try (BoundedDigestInputStream input =
        new BoundedDigestInputStream(
            new ByteArrayInputStream("abcd".getBytes(StandardCharsets.UTF_8)), 3)) {
      assertThrows(
          FileOperationException.class,
          () -> {
            while (input.read() != -1) {
              /* 读取至越界。 */
            }
          });
    } catch (Exception exception) {
      if (exception instanceof FileOperationException fileException) {
        throw fileException;
      }
      throw new RuntimeException(exception);
    }
  }
}
