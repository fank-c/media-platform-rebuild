package com.calles.platform.file.application.asset;

import com.calles.platform.file.domain.asset.FileAsset;
import com.calles.platform.file.interfaces.http.dto.FileResponses;

/** 文件领域对象到对外 DTO 的唯一映射位置，防止 controller 意外暴露 storage key 或 bucket。 */
public final class FileAssetViews {
  /** 工具类禁止实例化。 */
  private FileAssetViews() {}

  /**
   * @param asset 已鉴权的元数据
   * @return 不含内部存储定位信息的文件响应
   */
  public static FileResponses.Metadata metadata(FileAsset asset) {
    return new FileResponses.Metadata(
        asset.getId(),
        asset.getOriginName(),
        asset.getMime(),
        asset.getDeclaredSize(),
        asset.getSize(),
        asset.getSha256(),
        asset.getStatus().name(),
        asset.getUploadStatus().name(),
        asset.getUploadExpiresAt(),
        asset.getCreateAt(),
        asset.getUpdateAt());
  }
}
