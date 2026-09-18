package com.calles.platform.content.application.client;

/**
 * 从 file-service 远程查询得到的轻量文件元数据传输对象 (DTO)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：用于承载文件资产服务的元数据契约，防止内容服务直接访问文件数据库；</li>
 *   <li><b>协作对象</b>：由 {@link FileServiceClient} 返回，供 {@link com.calles.platform.content.application.video.VideoPublishApplicationService} 校验资产可用性；</li>
 *   <li><b>就绪准则</b>：只有处于 {@code CONFIRMED} 上传完成且 {@code ACTIVE} 可用状态的文件才被认定为就绪。</li>
 * </ul>
 * </p>
 *
 * @param fileId 文件全局唯一 ID (UUID 32位无短横线)
 * @param originName 原始文件名 (如 "intro.mp4")
 * @param mime MIME 媒体格式类型 (如 "video/mp4", "image/jpeg")
 * @param declaredSize 上传预申请时客户端声明的文件字节大小
 * @param actualSize 实际持久化写入 MinIO 对象存储后的物理字节大小
 * @param status 平台级可用状态 (ACTIVE=正常, DISABLED=违规封禁/停用)
 * @param uploadStatus 上传生命周期状态 (PENDING=待传, VERIFYING=校验中, CONFIRMED=已确认完成, REJECTED=校验未过, EXPIRED=过期失效)
 */
public record FileMetadataDTO(
        String fileId,
        String originName,
        String mime,
        long declaredSize,
        Long actualSize,
        String status,
        String uploadStatus
) {
    /**
     * 校验文件资产是否已处于已确认就绪 (CONFIRMED) 且正常可用 (ACTIVE) 状态。
     *
     * @return true 表示文件已成功上传至对象存储且通过完整性校验，可安全用于视频草稿与发布
     */
    public boolean isReady() {
        return ("CONFIRMED".equalsIgnoreCase(uploadStatus) || "COMPLETED".equalsIgnoreCase(uploadStatus))
                && "ACTIVE".equalsIgnoreCase(status);
    }
}
