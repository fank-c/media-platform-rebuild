# HTTP 接口契约全景文档

本文档汇聚 Calles 媒体平台全站微服务对外网关接口、内部微服务专有通信接口与管理治理端接口契约，供前后端联调、跨服务 Feign 调用与测试验收速查。

---

## 1. 全局调用约定与安全规范

### 1.1 网关接入与统一前缀
所有客户端业务请求统一发往 API 网关（默认端口 8000），路径保持统一业务前缀 `/api/**`，严禁客户端绕过网关直接直连下游微服务端口：
- 请求地址规范：`http://<gateway-host>:8000/api/<domain>/<resource>`

### 1.2 身份凭据与鉴权门禁
除明确列入白名单的匿名接口外，所有请求必须携带标准 Bearer Token：
```http
Authorization: Bearer <accessToken>
```
- **普通用户（`requireUser`）**：本人资料、草稿箱、提审、私人文件直传等接口，网关清洗并注入 `X-User-Id` 与 `X-User-Role: USER`；
- **管理治理端（`requireAdmin`）**：作品封禁、审核仲裁、全站资料纠偏等接口，严格校验 `X-User-Role: ADMIN`，普通用户访问直接拦截返回 `403 FORBIDDEN`；
- **微服务内部专用通道（`internal`）**：挂载于 `/api/<domain>/internal/**`，仅供集群受信内网通信（如 Feign 探活、专有结果回调、流切片托管上传），不向外网开放。

### 1.3 统一响应外壳
除文件删除成功返回 `204 NO_CONTENT` 及受控流代理直接返回二进制字节外，全站统一采用标准响应外壳：
```json
{
  "code": 200,
  "message": "ok",
  "data": { ... }
}
```
- 异常错误统一返回对应 HTTP 状态码（如 `400`, `401`, `403`, `404`, `409`, `429`, `500`），外壳中包含精确的中文错误描述。

---

## 2. 全站 HTTP 接口全景索引表

| 服务域 | 方法 | URI 路径 | 鉴权要求 | 用途说明 |
| :--- | :--- | :--- | :--- | :--- |
| **认证域**<br/>`auth` | `GET` | `/api/auth/ping` | 匿名白名单 | 认证微服务健康探活 |
| | `POST` | `/api/auth/register` | 匿名白名单 | 用户注册普通账号，触发异步资料建档 |
| | `POST` | `/api/auth/login` | 匿名白名单 | 密码登录，签发双 Token 与会话池注册 |
| | `POST` | `/api/auth/refresh` | 匿名白名单 | 一次性原子换取新双 Token |
| | `POST` | `/api/auth/logout` | `requireUser` | 注销当前设备会话并拉黑 Token JTI |
| | `GET` | `/api/auth/me` | `requireUser` | 查询当前登录账号核心摘要 |
| | `POST` | `/api/auth/verify` | 内部回源 | 专供网关校验 JWT 签名与有效载荷 |
| **用户域**<br/>`user` | `GET` | `/api/users/me` | `requireUser` | 查询当前登录用户全量个人私有资料 |
| | `PATCH`| `/api/users/me` | `requireUser` | 修改个人昵称、签名、生日、城市（带版本号） |
| | `GET` | `/api/users/{accountId}` | `requireUser` | 查看其他用户的公开名片摘要（防盗链脱敏） |
| | `POST` | `/api/users/batch` | `requireUser` | 批量查询多用户公开摘要（保持入参顺序） |
| | `POST` | `/api/users/admin/list` | `requireAdmin` | 管理端多条件组合分页筛选用户档案 |
| | `PATCH`| `/api/users/admin/{accountId}` | `requireAdmin` | 管理员强制修改违规资料（带版本控制） |
| **文件域**<br/>`file` | `POST` | `/api/files` | `requireUser` | 普通表单上传单文件（上限 20MB） |
| | `POST` | `/api/files/direct-upload/v2`| `requireUser` | 大文件直传 V2 申请（签发 staging 预签名 PUT） |
| | `POST` | `/api/files/{id}/confirm/v2` | `requireUser` | 大文件直传完成确认（HEAD 校验与原子转正） |
| | `GET` | `/api/files/{id}` | `requireUser` | 查询本人文件资产元数据与完成状态 |
| | `GET` | `/api/files/{id}/download-url` | `requireUser` | 申请私有文件的短期预签名 GET 下载地址 |
| | `GET` | `/api/files/{id}/view-url` | `requireUser` | 签发带有时效与防盗链 HMAC 签名的直链 |
| | `DELETE`| `/api/files/{id}` | `requireUser` | 本人逻辑删除资产（同步删除 MinIO 对象） |
| | `GET` | `/api/files/assets/{id}` | 匿名防盗链 | 受控静态资源代理流（HMAC 验签 + 304 缓存协商） |
| | `POST` | `/api/files/internal/upload` | 内部专有 | **内部微服务专属** 切片大文件托管上传 |
| | `GET` | `/api/files/internal/{id}/download-url`| 内部专有 | **内部微服务专属** 按 ID 换取预签名 GET 直链 |
| **内容域**<br/>`content` | `POST` | `/api/content/videos/draft` | `requireUser` | 创作者新建草稿，签发 Base62 短码 `vid` |
| | `PUT` | `/api/content/videos/{id}` | 作者本人 | 更新视频标题、简介、封面图与关联标签 |
| | `POST` | `/api/content/videos/{id}/submit`| 作者本人 | **Feign 文件强探活** 校验后提审，派发任务网格 |
| | `POST` | `/api/content/videos/{id}/offline`| 作者本人 | 创作者主动下架视频 |
| | `DELETE`| `/api/content/videos/{id}` | 作者本人 | 逻辑删除视频并回收标签引用热度 |
| | `GET` | `/api/content/videos/me` | `requireUser` | 创作者工作台分页作品列表 |
| | `GET` | `/api/content/videos/{id}/tasks` | 作者本人 | 实时查询 5 类流水线任务执行进度与就绪态 |
| | `GET` | `/api/content/videos/{vid}` | 公开/脱敏 | 依据公开短码查询图文详情（私密/封禁脱敏为404）|
| | `GET` | `/api/content/videos/{vid}/streams` | 公开/脱敏 | 聚合查询有效播放流列表（4K/1080P/720P/360P）|
| | `GET` | `/api/content/tags/hot` | 公开 | 查询全站热度前 N 名的推荐标签 |
| | `POST` | `/api/content/videos/admin/list` | `requireAdmin`| 管理端多维条件组合分页检索作品 |
| | `POST` | `/api/content/videos/admin/{id}/ban` | `requireAdmin`| 违规封禁作品（`DISABLED` 并广播全站下线） |
| | `POST` | `/api/content/videos/admin/{id}/unban`| `requireAdmin`| 解封恢复作品（`ACTIVE` 并重新激活流） |
| | `POST` | `/api/content/videos/internal/audit-callback` | 内部受信网络 | 接收审核微服务判定结果，驱动发布门禁 |
| | `POST` | `/api/content/videos/internal/transcode-callback`| 内部受信网络 | 接收转码微服务切片资产登记，驱动发布门禁 |
| | `POST` | `/api/content/videos/internal/task-callback` | 内部受信网络 | 通用 Worker 执行进度（0-100%）与无实体任务完成汇报 |
| **审核域**<br/>`audit` | `POST` | `/api/audit/callback/aliyun` | 外部 Webhook | 接收阿里云内容安全异步机审通知（带签名防篡改）|
| | `POST` | `/api/audit/admin/tasks/page` | `requireAdmin`| 管理端分页筛选多维度审核工单 |
| | `GET` | `/api/audit/admin/tasks/{id}` | `requireAdmin`| 查看审核工单详情与文本/图片/视频机审证据快照 |
| | `POST` | `/api/audit/admin/tasks/{id}/approve` | `requireAdmin`| 人工复审通过，触发 Feign 回调内容服务放行 |
| | `POST` | `/api/audit/admin/tasks/{id}/reject` | `requireAdmin`| 人工复审驳回，触发 Feign 回调内容服务熔断 |

---

## 3. 核心领域接口报文样例

### 3.1 视频提审（`POST /api/content/videos/{id}/submit`）
- **请求头**：`Authorization: Bearer <token>`
- **前置硬要求**：引用的 `videoFileId` 与 `coverFileId` 必须在 `file-service` 中已确认为 `COMPLETED` 且 `ACTIVE`；
- **成功响应（HTTP 200）**：
  ```json
  {
    "code": 200,
    "message": "ok",
    "data": {
      "id": "e0b51f0b4d4f40f09a562095f9d45e01",
      "vid": "cv05hG9Kq2RtLw7XbPmZv4Ya",
      "publishStatus": "AUDITING",
      "submittedAt": "2026-09-17T15:30:00.123Z"
    }
  }
  ```

### 3.2 审核专有内部回调（`POST /api/content/videos/internal/audit-callback`）
- **请求头**：内部受信 RPC 请求
- **请求载荷**：
  ```json
  {
    "videoId": "e0b51f0b4d4f40f09a562095f9d45e01",
    "result": "PASS",
    "rejectReason": null,
    "reviewLevel": "NORMAL"
  }
  ```
- **业务效果**：内容服务自动将 `AUDIT` 子任务置为 `SUCCESS`，并立即触发 `PublishGatekeeper` 门禁重新决策。

### 3.3 转码专有内部回调（`POST /api/content/videos/internal/transcode-callback`）
- **请求头**：内部受信 RPC 请求
- **请求载荷**：
  ```json
  {
    "videoId": "e0b51f0b4d4f40f09a562095f9d45e01",
    "quality": "1080P",
    "format": "MP4",
    "codec": "H264",
    "fileId": "f789abc01234567890abcdef12345678",
    "fileSize": 15843920,
    "bitrate": 3500,
    "fps": 30,
    "duration": 185
  }
  ```
- **业务效果**：内容服务持久化 `video_stream` 切片，更新转码子任务为 `SUCCESS`，若基准清晰度就绪则触发视频自动上线发布。
