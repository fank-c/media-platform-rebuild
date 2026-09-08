package com.calles.platform.file.infrastructure.storage;

import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.domain.asset.StorageType;
import com.calles.platform.file.exception.FileOperationException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 将受控存储枚举映射到显式注册的适配器。
 *
 * <p>未知类型、缺失注册和重复注册均启动或运行失败，绝不默认回退到 MinIO 防止对象误读误删。
 */
@Component
public class StorageFactory {
  /** 已注册适配器，以 storageType 为唯一键。 */
  private final Map<StorageType, ObjectStorageClient> clients = new EnumMap<>(StorageType.class);

  /** @param registered Spring 注入的存储适配器集合 */
  public StorageFactory(List<ObjectStorageClient> registered) {
    for (ObjectStorageClient client : registered) {
      if (clients.putIfAbsent(client.storageType(), client) != null) {
        throw new IllegalStateException("重复注册对象存储类型: " + client.storageType());
      }
    }
  }

  /**
   * 获取已有文件或新文件指定的适配器。
   *
   * @param storageType 行内或配置确认后的类型
   * @return 对应对象存储适配器
   */
  public ObjectStorageClient require(StorageType storageType) {
    ObjectStorageClient client = clients.get(storageType);
    if (client == null) {
      throw new FileOperationException(HttpStatus.SERVICE_UNAVAILABLE, "文件存储类型未配置");
    }
    return client;
  }
}
