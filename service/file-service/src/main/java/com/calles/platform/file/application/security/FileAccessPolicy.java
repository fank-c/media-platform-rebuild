package com.calles.platform.file.application.security;

import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.file.exception.FileOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 文件 HTTP 入口的显式身份策略。
 *
 * <p>首期仅允许普通用户操作自己的私有文件；管理员不会因角色自动取得他人文件访问权。
 */
@Component
public class FileAccessPolicy {
  /**
   * @return 已认证普通用户身份
   * @throws FileOperationException 无身份或主体不允许时抛出
   */
  public UserInfo requireUser() {
    UserInfo user =
        UserContext.get()
            .orElseThrow(() -> new FileOperationException(HttpStatus.UNAUTHORIZED, "缺少有效身份"));
    if (!user.isUser() || user.isAdmin()) {
      throw new FileOperationException(HttpStatus.FORBIDDEN, "当前身份不能操作私人文件");
    }
    return user;
  }
}
