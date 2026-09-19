package com.calles.platform.file.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.domain.asset.StorageType;
import com.calles.platform.file.exception.FileOperationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 存储路由工厂的单元测试，验证适配器注册、单类型路由与重复防御。 */
class StorageFactoryTest {

  /** 已注册的 ALIYUN_OSS 适配器应能被正确获取。 */
  @Test
  void resolvesRegisteredOssClient() {
    ObjectStorageClient client = mock(ObjectStorageClient.class);
    when(client.storageType()).thenReturn(StorageType.ALIYUN_OSS);

    StorageFactory factory = new StorageFactory(List.of(client));
    ObjectStorageClient resolved = factory.require(StorageType.ALIYUN_OSS);

    assertNotNull(resolved);
    assertEquals(client, resolved);
  }

  /** 当 ALIYUN_OSS 与 MINIO 同时注册时，工厂应能按类型精准路由到对应客户端。 */
  @Test
  void resolvesBothOssAndMinioClientsWhenCoexisting() {
    ObjectStorageClient ossClient = mock(ObjectStorageClient.class);
    when(ossClient.storageType()).thenReturn(StorageType.ALIYUN_OSS);

    ObjectStorageClient minioClient = mock(ObjectStorageClient.class);
    when(minioClient.storageType()).thenReturn(StorageType.MINIO);

    StorageFactory factory = new StorageFactory(List.of(ossClient, minioClient));

    assertEquals(ossClient, factory.require(StorageType.ALIYUN_OSS));
    assertEquals(minioClient, factory.require(StorageType.MINIO));
  }

  /** 请求未注册的存储类型应明确抛出 503 SERVICE_UNAVAILABLE。 */
  @Test
  void throwsOnUnregisteredStorageType() {
    StorageFactory factory = new StorageFactory(List.of());

    FileOperationException exception =
        assertThrows(FileOperationException.class, () -> factory.require(StorageType.ALIYUN_OSS));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
  }

  /** 重复注册同一种存储类型时应拒绝启动并抛出 IllegalStateException。 */
  @Test
  void throwsOnDuplicateRegistration() {
    ObjectStorageClient client1 = mock(ObjectStorageClient.class);
    when(client1.storageType()).thenReturn(StorageType.ALIYUN_OSS);

    ObjectStorageClient client2 = mock(ObjectStorageClient.class);
    when(client2.storageType()).thenReturn(StorageType.ALIYUN_OSS);

    assertThrows(IllegalStateException.class, () -> new StorageFactory(List.of(client1, client2)));
  }
}
