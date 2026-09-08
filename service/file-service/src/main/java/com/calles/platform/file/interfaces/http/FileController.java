package com.calles.platform.file.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.file.application.asset.FileAssetApplicationService;
import com.calles.platform.file.application.confirmation.DirectUploadConfirmationService;
import com.calles.platform.file.application.security.FileAccessPolicy;
import com.calles.platform.file.exception.FileOperationException;
import com.calles.platform.file.interfaces.http.dto.DirectUploadRequest;
import com.calles.platform.file.interfaces.http.dto.FileResponses;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 文件 HTTP 协议适配器，只处理参数、真实状态码和本人身份传递，不承载对象存储或状态机逻辑。 */
@RestController
@RequestMapping("/api/files")
public class FileController {
  /** HTTP 身份策略。 */
  private final FileAccessPolicy accessPolicy;

  /** 同步上传、查询、签名和删除用例。 */
  private final FileAssetApplicationService fileService;

  /** 预签名确认用例。 */
  private final DirectUploadConfirmationService confirmationService;

  /**
   * @param accessPolicy 身份策略
   * @param fileService 文件用例
   * @param confirmationService 确认用例
   */
  public FileController(
      FileAccessPolicy accessPolicy,
      FileAssetApplicationService fileService,
      DirectUploadConfirmationService confirmationService) {
    this.accessPolicy = accessPolicy;
    this.fileService = fileService;
    this.confirmationService = confirmationService;
  }

  /** 普通 multipart 上传；成功后只在完成元数据提交明确成功时返回 201。 */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<FileResponses.Metadata>> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String storageType) {
    UserInfo user = accessPolicy.requireUser();
    return ResponseEntity.status(201)
        .body(ApiResponse.ok(fileService.upload(user.userId(), file, storageType)));
  }

  /** 创建 PENDING 直传记录和短期 PUT 签名；业务完成仍需客户端后续 confirm。 */
  @PostMapping(path = "/direct-upload", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<FileResponses.DirectUpload>> initializeDirectUpload(
      @RequestBody DirectUploadRequest request) {
    UserInfo user = accessPolicy.requireUser();
    return ResponseEntity.status(201)
        .body(ApiResponse.ok(fileService.initializeDirectUpload(user.userId(), request)));
  }

  /** 已完成返回 200；首次提交和同进程在途请求返回真实 HTTP 202。 */
  @PostMapping("/{id}/confirm")
  public ResponseEntity<ApiResponse<?>> confirm(@PathVariable String id) {
    UserInfo user = accessPolicy.requireUser();
    try {
      Object response = confirmationService.confirm(user.userId(), id);
      if (response instanceof FileResponses.ConfirmationAccepted accepted) {
        return ResponseEntity.accepted().body(ApiResponse.ok(accepted));
      }
      return ResponseEntity.ok(ApiResponse.ok(response));
    } catch (FileOperationException exception) {
      if (exception.getStatus().value() == 503) {
        // 队列拒绝给出固定轮询退避，避免客户端立即重试继续压满队列。
        return ResponseEntity.status(503)
            .header(HttpHeaders.RETRY_AFTER, "2")
            .body(new ApiResponse<>(503, exception.getMessage(), null));
      }
      throw exception;
    }
  }

  /** 查询本人未删除文件的元数据，不因 GET 触发确认或对象读取。 */
  @GetMapping("/{id}")
  public ApiResponse<FileResponses.Metadata> get(@PathVariable String id) {
    UserInfo user = accessPolicy.requireUser();
    return ApiResponse.ok(fileService.get(user.userId(), id));
  }

  /** 返回 no-store 的短期下载凭据，业务 API 仍经网关认证。 */
  @GetMapping("/{id}/download-url")
  public ResponseEntity<ApiResponse<FileResponses.DownloadUrl>> downloadUrl(
      @PathVariable String id) {
    UserInfo user = accessPolicy.requireUser();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.ok(fileService.downloadUrl(user.userId(), id)));
  }

  /** 本人删除执行远端先行、墓碑后写；已存在本人的墓碑仍返回 204。 */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    UserInfo user = accessPolicy.requireUser();
    fileService.delete(user.userId(), id);
    return ResponseEntity.noContent().build();
  }
}
