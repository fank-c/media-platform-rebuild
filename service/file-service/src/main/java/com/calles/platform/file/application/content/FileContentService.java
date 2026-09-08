package com.calles.platform.file.application.content;

import com.calles.platform.file.application.port.FileAssetRepository;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.domain.asset.AssetStatus;
import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.domain.asset.UploadStatus;
import com.calles.platform.file.exception.FileOperationException;
import com.calles.platform.file.infrastructure.storage.StorageFactory;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * file-service 进程内的已完成文件内容读取能力。
 *
 * <p>它不是跨 JVM API：其他服务不得直接注入、共享实体或伪造 actorUserId 调用此类。
 */
@Service
public class FileContentService {
  /** 元数据查询端口。 */
  private final FileAssetRepository repository;

  /** 按行内存储类型选择受控适配器。 */
  private final StorageFactory storageFactory;

  /**
   * @param repository 文件元数据仓储
   * @param storageFactory 存储适配器工厂
   */
  public FileContentService(FileAssetRepository repository, StorageFactory storageFactory) {
    this.repository = repository;
    this.storageFactory = storageFactory;
  }

  /**
   * 在每次打开前重新检查本人所有权、逻辑删除、资源可用和完成状态，再返回独立流。
   *
   * @param actorUserId 经过当前用例认证的主体 ID
   * @param fileId 文件 ID
   * @return 调用方必须关闭的读取句柄
   */
  public FileContentResource openForProcessing(String actorUserId, String fileId) {
    FileAsset asset = requireVisible(actorUserId, fileId);
    if (asset.getStatus() != AssetStatus.ACTIVE
        || asset.getUploadStatus() != UploadStatus.COMPLETED) {
      throw new FileOperationException(HttpStatus.CONFLICT, "文件尚不可读取");
    }
    ObjectStorageClient client = storageFactory.require(asset.getStorageType());
    InputStream stream = null;
    try {
      // 打开后立即构造资源；构造失败时 finally 仍会关闭底层连接。
      stream = client.open(asset.getStorageKey());
      return new FileContentResource(asset, stream);
    } catch (RuntimeException exception) {
      if (stream != null) {
        try {
          stream.close();
        } catch (Exception ignored) {
        }
      }
      throw exception;
    }
  }

  /** 隐藏未知、他人和已删除记录，避免 ID 枚举。 */
  private FileAsset requireVisible(String owner, String id) {
    return repository
        .findVisibleByIdAndOwner(id, owner)
        .orElseThrow(() -> new FileOperationException(HttpStatus.NOT_FOUND, "文件不存在"));
  }
}
