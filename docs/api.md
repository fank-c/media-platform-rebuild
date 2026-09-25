# HTTP 接口文档

按当前代码整理，更新于 2026-09-18。本文供接口调用时查询请求、响应、权限和关键限制；实现流程分别见[认证](modules/auth.md)、[用户](modules/user.md)、[文件](modules/file.md)、[内容](modules/content.md)、[审核](modules/audit.md)、[转码](modules/transcode.md)文档。代码已存在不表示接口已在真实环境启用或验收。

## 1. 调用约定

### 地址与身份

业务请求发送至网关，以下路径均相对网关地址，例如 `<gateway-base>/api/users/me`。仓库网关默认端口为 8000，实际地址由运行环境提供；不要直接调用业务服务来绕过网关。

除明确注明匿名的接口外，请携带：

```http
Authorization: Bearer <accessToken>
```

- **匿名**：无需已有访问令牌，但登录仍需密码，刷新仍需刷新凭据。
- **已登录**：可以是普通用户或管理员。
- **普通用户**：本人资料与私人文件接口不接受管理员身份替代普通用户身份。
- **管理员**：仅管理端资料接口。

不要自行提交 `X-User-*` 或 `X-Session-Id` 来声明身份；这些 Header 由网关清理并重新注入。JSON 请求使用 `Content-Type: application/json`；普通文件上传使用 multipart，边界由客户端库生成。

### 响应与错误

除文件删除成功外，以下业务接口使用统一响应外壳：

```json
{
  "code": 200,
  "message": "ok",
  "data": null
}
```

`data` 才是各接口的业务结果，下文的“返回字段”均指 `data`。必须同时查看真实 HTTP 状态：文件上传 `201`、确认受理 `202` 的成功外壳也使用 `code=200`，不代表上传已完成。删除成功 `204` 没有正文。

受控错误通常返回对应 HTTP 状态、数值 `code`、中文 `message` 和 `data=null`。例如资料版本冲突：

```json
{
  "code": 409,
  "message": "资料版本已变化，请重新获取后再修改",
  "data": null
}
```

认证代码中的 `AUTH_*` 内部错误标识未作为独立字段返回，不要据此编写客户端响应解析。各节列出关键错误，不代表穷举所有网关、框架或依赖错误；未预期异常可能返回 `500`。尤其文件接口当前未单独把所有 JSON 解析错误映射为 `400`，不要假定所有非法请求都有统一错误分类。

可选请求头 `X-Trace-Id` 用于追踪；Servlet 服务接收合法标识或生成新标识，并在响应中返回。凭据、预签名地址不应写入日志。本文中的尖括号内容是占位值，不是可直接使用的凭据。

### 接口索引

| 方法 | 路径 | 身份 | 用途 |
| --- | --- | --- | --- |
| GET | `/api/auth/ping` | 匿名 | 认证服务连通性探针 |
| POST | `/api/auth/register` | 匿名 | 注册普通账号 |
| POST | `/api/auth/login` | 匿名 | 登录 |
| POST | `/api/auth/refresh` | 匿名，需刷新凭据 | 换取新凭据 |
| POST | `/api/auth/logout` | 已登录 | 退出当前会话 |
| GET | `/api/auth/me` | 已登录 | 当前账号摘要 |
| POST | `/api/auth/verify` | 网关内部回源用途 | 验证访问令牌 |
| GET | `/api/users/me` | 普通用户 | 查询本人资料 |
| PATCH | `/api/users/me` | 普通用户 | 首次完善或修改本人资料 |
| GET | `/api/users/{accountId}` | 已登录 | 单个公开资料 |
| POST | `/api/users/batch` | 已登录 | 批量公开摘要 |
| POST | `/api/users/admin/list` | 管理员 | 筛选分页资料 |
| PATCH | `/api/users/admin/{accountId}` | 管理员 | 修改已有正常资料 |
| POST | `/api/users/{targetUserId}/follow` | 普通用户 | 关注指定用户 |
| DELETE | `/api/users/{targetUserId}/follow` | 普通用户 | 取消关注用户 |
| GET | `/api/users/{targetUserId}/relation` | 开放/已登录 | 查询双方社交关系 |
| GET | `/api/users/{accountId}/following` | 已登录 | 分页查询关注列表 |
| GET | `/api/users/{accountId}/followers` | 已登录 | 分页查询粉丝列表 |
| GET | `/api/users/{accountId}/stats` | 已登录 | 查询用户关系统计快照 |
| GET | `/api/users/internal/{accountId}/following-ids` | 内部微服务 | 内部提取关注博主ID列表 |
| POST | `/api/files` | 普通用户 | 普通上传 |
| POST | `/api/files/direct-upload` | 普通用户 | V1 直传初始化 |
| POST | `/api/files/direct-upload/v2` | 普通用户；默认关闭 | V2 直传初始化（新入口已过期，不推荐启用；保留历史路由） |
| POST | `/api/files/{id}/confirm` | 普通用户 | V1 兼容确认 |
| POST | `/api/files/{id}/confirm/v2` | 普通用户 | V2 确认 |
| GET | `/api/files/{id}` | 普通用户 | 本人文件元数据 |
| GET | `/api/files/{id}/download-url` | 普通用户 | 申请短期下载地址 |
| DELETE | `/api/files/{id}` | 普通用户 | 删除本人文件 |
| POST | `/api/content/videos/draft` | 普通用户 (创作者) | 创建视频草稿 (HTTP 201) |
| PUT | `/api/content/videos/{id}` | 作者本人 / 管理员 | 修改视频图文元数据 |
| POST | `/api/content/videos/{id}/submit` | 作者本人 | 提审并触发文件探活与流水线 |
| POST | `/api/content/videos/{id}/offline` | 作者本人 | 主动下架视频并下线索引 |
| DELETE | `/api/content/videos/{id}` | 作者本人 | 删除草稿或下架视频 (HTTP 204) |
| GET | `/api/content/videos/me` | 普通用户 (创作者) | 创作者工作台作品分页检索 |
| GET | `/api/content/videos/{id}/tasks` | 作者本人 / 管理员 | 查询发布流水线子任务与门禁进度 |
| GET | `/api/content/videos/{vid}` | 匿名 / 已登录 | 根据业务短码查询视频图文详情 |
| GET | `/api/content/videos/{vid}/streams` | 匿名 / 已登录 | 根据业务短码查询可用播放流列表 |
| GET | `/api/content/tags/hot` | 匿名 | 获取全站热门轻量标签列表 |
| POST | `/api/content/videos/admin/list` | 管理员 | 管理后台综合多条件检索视频 |
| POST | `/api/content/videos/admin/{id}/ban` | 管理员 | 管理端违规封禁视频并拉黑 |
| POST | `/api/content/videos/admin/{id}/unban` | 管理员 | 管理端解除封禁恢复上线 |
| POST | `/api/content/videos/internal/audit-callback` | 内部微服务 (audit) | 接收机审判定结果回调 |
| POST | `/api/content/videos/internal/transcode-callback` | 内部微服务 (transcode) | 接收切片转码产物注册回调 |
| POST | `/api/content/videos/internal/task-callback` | 内部微服务 (Worker) | 接收异步任务进度与状态汇报 |
| GET | `/api/audit/admin/tasks` | 管理员 | 管理端分页检索审核工单列表 |
| GET | `/api/audit/admin/tasks/{id}` | 管理员 | 查询工单全景与机审证据明细 |
| POST | `/api/audit/admin/tasks/{id}/review` | 管理员 | 管理员执行人工复审裁决 (通过/驳回) |
| POST | `/api/audit/callback/aliyun/video` | 匿名验签 (云厂商) | 接收阿里云视频机审异步 Webhook 通知 |
| POST | `/api/audit/internal/submit` | 内部微服务 | 内部模拟提审触发机审流水线 |
| GET | `/api/audit/tasks/{id}` | 内部微服务 | 内部查询审核工单与判定明细 |
| GET | `/api/transcode/tasks/{id}` | 内部微服务 / 管理员 | 按工单 ID 查询流媒体转码任务详情 |
| POST | `/api/transcode/tasks/trigger` | 内部微服务 / 管理员 | 手动触发指定画质切片转码流水线 |
| POST / DELETE | `/api/interactions/videos/{vid}/like` | 已登录 | 点赞 / 取消点赞 |
| POST / DELETE | `/api/interactions/videos/{vid}/star` | 已登录 | 收藏 / 取消收藏 |
| GET / POST | `/api/interactions/star/folders` | 已登录 | 收藏夹列表 / 新建自定义收藏夹 |
| GET | `/api/interactions/star/items` | 已登录 | 分页查询收藏夹内视频 |
| POST | `/api/interactions/videos/{vid}/heartbeat` | 已登录 | 上报播放心跳 |
| GET | `/api/interactions/videos/{vid}/watch-progress` | 已登录（服务内允许匿名） | 查询断点进度 |
| GET / DELETE | `/api/interactions/watch/history` | 已登录 | 观看历史分页 / 删除单条或清空 |
| GET | `/api/interactions/videos/{vid}/my-state` | 已登录（服务内允许匿名） | 播放页互动状态快照 |
| GET | `/api/interactions/videos/{vid}/stat` | 已登录（服务内允许匿名） | 单视频公开计数 |
| POST | `/api/interactions/videos/stats` | 已登录（服务内允许匿名） | 批量视频公开计数 |
| POST | `/api/interactions/videos/{vid}/share` | 已登录，需 `Idempotency-Key` | 记录分享 |
| GET | `/api/recommend/feed` | 已登录（服务内允许匿名） | 首页推荐流 |
| POST | `/api/recommend/feedback` | 已登录（服务内允许匿名） | 上报曝光 / 播放 / 跳过 / 负反馈 |
| GET / POST / DELETE | `/api/recommend/blocks` | 已登录 | 查询 / 新增 / 撤销推荐屏蔽 |

推荐接口与互动接口一样，经网关时全部需要令牌（`/api/recommend/**` 不在白名单）。互动接口经网关时全部需要令牌（`/api/interactions/**` 不在白名单）；标注“服务内允许匿名”的接口仅在绕过网关直连时对游客返回默认值。各微服务内部回调与受控端点（挂载于 `/internal/**`）由网关统一拦截，仅限集群内网受信通信。网关另配置 `/actuator/health`、`/actuator/info` 白名单作为管理探针，不代表所有下游管理端点对外开放。

## 2. 认证接口

### 注册：POST /api/auth/register

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `email` | string | 是 | 符合标准邮箱格式，最多 255 字符；应用层统一 trim 并转小写 |
| `password` | string | 是 | 至少 8 个字符，最多 72 个 UTF-8 字节；HTTP 字符长度上限也为 72 |

```json
{
  "email": "example_user@example.com",
  "password": "<符合长度要求的测试密码>"
}
```

成功 `200`：返回 `accountId`、`email`、`role`、`status`，均为字符串；新账号固定 `USER / ACTIVE`。账号 ID 是无连字符的 32 位十六进制字符串。**不返回令牌，不自动登录，资料异步初始化。**

关键错误：输入校验失败 `400`；预先查到邮箱已注册 `409`。并发重名可能触发数据库重复键异常并走通用 `500`，当前未统一映射为 `409`。

### 登录：POST /api/auth/login

请求头（可选）：
- `X-Device-Id`：客户端设备唯一标识（最多 128 字符）。推荐在客户端全局拦截器中统一注入；服务端优先使用此 Header，缺失时回退读取请求体 `deviceId`，均缺失时自动生成随机 UUID 向后兼容。

JSON 必填 `email`、`password`，可选 `deviceId`。邮箱非空、符合邮箱格式、最多 255 字符，应用层去首尾空白并转小写；密码非空，HTTP 最多 72 字符且应用层复核不超过 72 个 UTF-8 字节；`deviceId` 最多 128 字符。同一设备重复登录将覆盖该设备旧会话而非新增会话；超出最大会话限制时自动淘汰最久未活跃会话。

成功 `200`：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `accessToken` | string | 业务 API 使用的访问令牌 |
| `refreshToken` | string | 一次性轮换凭据，不能作为业务 API 的 Bearer 令牌 |
| `expiresIn` | integer | 访问令牌有效秒数，默认 900，不是刷新凭据有效期 |
| `role` | string | 签发时角色，`USER` 或 `ADMIN` |

关键错误：输入不合法 `400`；账号不存在或密码错误 `401`；禁用账号单独返回 `403`；会话存储不能可靠完成操作时可能返回 `503`。

### 刷新：POST /api/auth/refresh

```json
{"refreshToken":"<refreshToken>"}
```

`refreshToken` 为必填非空字符串。不要求有效访问令牌。成功 `200`，返回与登录相同的四个字段；调用方应替换原来的两种凭据。

关键错误：空字段 `400`；过期、重复使用或无效刷新凭据 `401`；账号禁用 `403`；会话不可用可能为 `503`。旧凭据在轮换开始后即被消费，后续失败不恢复它。不要多个请求并行使用同一刷新凭据，也不要假设超时后原样重试一定有效。

### 退出：POST /api/auth/logout

请求仅需访问令牌，无 JSON 正文。成功 `200`，`data=null`。服务删除当前刷新会话，并撤销这次提交的访问令牌。

无效访问令牌 `401`，会话操作不可用可能为 `503`。它不是全部设备退出，也不撤销所有历史访问令牌；网关缓存清理为尽力操作，存在失败和并发窗口，详见[网关限制](modules/gateway.md)。

### 当前账号：GET /api/auth/me

无查询参数。成功 `200` 返回：`accountId`、`email`、`role`（`USER/ADMIN`）、`type`（`user/admin`）、`sessionId`，均为字符串。服务读取最新账号数据，但响应**没有 `status` 字段**，也不包含昵称、简介等用户资料。

关键错误：令牌无效或账号不存在 `401`；账号禁用 `403`。需要用户资料时使用 `/api/users/me`。

### 令牌验证：POST /api/auth/verify

供网关内部验证调用使用，不是客户端登录入口，也未列入网关匿名白名单。客户端不能据此绕过网关认证，更不应为调用此接口直连认证服务。

请求 JSON：`{"token":"<accessToken>"}`，`token` 必填非空字符串，可带 `Bearer ` 前缀。

成功处理请求为 HTTP `200`，返回：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `valid` | boolean | 是否通过令牌验证 |
| `subject` | string / null | 账号 ID |
| `type` | string / null | `user` 或 `admin` |
| `role` | string / null | `USER` 或 `ADMIN` |
| `sessionId` | string / null | 会话 ID |
| `expiresAt` | integer / null | 令牌过期的 Unix 毫秒时间戳 |

验证失败返回 `valid=false`，其余字段为 `null`；缺失或空请求字段仍是输入错误 `400`。此接口不实时查询账号状态，也不确认用户资料是否已创建。

### 探针：GET /api/auth/ping

匿名，无请求正文。成功 `200`，`data="auth-service"`。只能说明该请求到达并获得响应，不代表数据库、Redis 和消息链路均正常。

## 3. 用户资料接口

### 资料响应字段

各视图不是同一份数据的无限制返回，调用方应按用途选择：

| 视图 | 返回字段 |
| --- | --- |
| 本人资料 | `accountId`, `profileState`, `revision`, `nickname`, `avatarUrl`, `bio`, `city`, `gender`, `birthday`, `createdAt`, `updatedAt` |
| 单条公开资料 | `accountId`, `nickname`, `avatarUrl`, `bio` |
| 批量项的 `profile` | `accountId`, `nickname`, `avatarUrl` |
| 管理资料 | `accountId`, `revision`, `nickname`, `avatarUrl`, `bio`, `city`, `gender`, `birthday`, `status`, `createdAt`, `updatedAt` |

ID、状态及文本字段为字符串，`revision` 为非负整数，`gender` 为可空整数，当前接口不开放编辑或提供可选值契约；`birthday` 为可空日期，请求格式 `YYYY-MM-DD`。`createdAt/updatedAt` 为可空日期时间（Java `LocalDateTime`，不携带时区）；不要将它们按 Unix 毫秒解析。昵称、头像及其他未填写资料字段允许为空。

头像只展示符合可信地址前缀的已有值；默认前缀为空时隐藏头像。查询字段存在不代表可编辑该字段。

### 本人查询：GET /api/users/me

普通用户，无请求参数。成功 `200`，返回本人资料视图：

- 资料缺失：`profileState=PENDING`、`revision=0`，资料和时间字段为空；不会因为 GET 自动建档。
- 资料存在且正常：`profileState=READY`，返回当前资料与版本。

关键错误：管理员或不允许的身份 `403`；本人资料停用 `403`；本人资料删除 `410`。缺失资料可稍后再查询，或调用首次保存。

### 本人编辑：PATCH /api/users/me

普通用户。请求如下，管理端 PATCH 也使用相同字段规则：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `revision` | integer | 是 | 最近查询/保存返回的版本，非负；缺失资料首次保存为 0 |
| `nickname` | string / null | 至少提交一个可编辑字段 | 去首尾空白后最多 64 字符；非 null 时不能全为空白 |
| `bio` | string / null | 同上 | 去首尾空白后最多 500 字符 |
| `city` | string / null | 同上 | 去首尾空白后最多 100 字符 |
| `birthday` | string / null | 同上 | `YYYY-MM-DD`，不能晚于当天 |

```json
{
  "revision": 0,
  "nickname": "示例昵称",
  "bio": null
}
```

未提交某字段表示保持不变，显式 `null` 表示清空。`avatarUrl`、`gender`、`status` 不是可编辑字段。

成功 `200`，返回本人资料，新版本递增。资料缺失时允许创建并保存；错误输入 `400`，版本冲突 `409`，生命周期限制同本人查询。遇到 `409` 应重新读取后确认修改，不要盲目重试旧版本。

### 公开查询：GET /api/users/{accountId}

任意已登录主体可调用；`accountId` 必须为 32 位十六进制字符串。成功 `200` 返回单条公开资料。ID 格式错误 `400`；不存在、停用、删除统一 `404`。这里的“公开”不等于匿名。

### 批量摘要：POST /api/users/batch

任意已登录主体。JSON 的 `accountIds` 为必填字符串数组，1–100 项，每个 ID 遵守相同格式：

```json
{"accountIds":["00000000000000000000000000000001"]}
```

以上为示例 ID，不保证存在。成功 `200`，`data` 为数组，每项包含 `accountId`、布尔值 `available`、可空的 `profile`。顺序和重复项与请求一致；不存在、停用、删除的资料对应 `available=false, profile=null`，不是整批报 `404`。数量或 ID 格式错误返回 `400`。

### 管理列表：POST /api/users/admin/list

管理员。查询参数 `page` 默认 1、最小 1；`size` 默认 20、范围 1–100，均为整数。JSON 正文可省略，过滤字段均可选：

```json
{
  "nicknamePrefix": "示例",
  "status": "ACTIVE"
}
```

支持 `accountId` 精确匹配、`nicknamePrefix` 去首尾空白后最多 64 字符、`status=ACTIVE/DISABLED`。成功 `200`，返回 `records`（管理资料视图数组）、`total`、`current`、`size`（均为整数）。排除逻辑删除资料，按创建时间和账号 ID 排序。

普通用户 `403`；业务校验发现非法筛选或分页范围返回 `400`。此接口不查询认证角色和密码。

### 管理编辑：PATCH /api/users/admin/{accountId}

管理员，路径账号 ID 规则同上，正文使用本人 PATCH 的字段和版本规则。成功 `200`，返回管理资料视图。

只修改已有正常资料：不存在或已删除 `404`；停用资料或版本冲突 `409`；参数不合法 `400`；非管理员 `403`。不提供新建、恢复、启停资料或修改认证角色能力。

### 关注用户：POST /api/users/{targetUserId}/follow

普通用户，关注目标创作者。成功 `200`，支持严格幂等（重复操作不累加计数），禁止关注自己。

- 关键错误：未登录 `401`；关注自己 `400`；目标用户不存在或已被封禁 `404`。
- 成功响应：
  ```json
  {
    "code": 200,
    "message": "ok",
    "data": {
      "targetUserId": "u_1002",
      "followStatus": 1,
      "mutual": false
    }
  }
  ```

### 取消关注：DELETE /api/users/{targetUserId}/follow

普通用户，取消关注目标用户。成功 `200`，软状态置零，具备天然幂等性。

- 关键错误：未登录 `401`；取关自己 `400`。
- 成功响应：
  ```json
  {
    "code": 200,
    "message": "ok",
    "data": {
      "targetUserId": "u_1002",
      "followStatus": 0,
      "mutual": false
    }
  }
  ```

### 关系查询：GET /api/users/{targetUserId}/relation

开放接口（支持未登录/已登录访问）。查询当前登录用户与目标用户的社交拓扑关系。

- 关系枚举：`NONE`（无关系）、`FOLLOWING`（我关注了他）、`FOLLOWED_BY`（他关注了我）、`MUTUAL`（互相关注）。
- 成功响应：
  ```json
  {
    "code": 200,
    "message": "ok",
    "data": {
      "targetUserId": "u_1002",
      "relation": "MUTUAL"
    }
  }
  ```

### 关注列表：GET /api/users/{accountId}/following

已登录用户，按时间倒序分页查询目标用户的关注列表。支持 `page`（默认 1）和 `size`（默认 20）。

- 成功响应：
  ```json
  {
    "code": 200,
    "message": "ok",
    "data": {
      "total": 1,
      "page": 1,
      "size": 20,
      "items": [
        {
          "accountId": "u_1002",
          "nickname": "极客数码",
          "avatarUrl": "https://img.calles.com/avatar.jpg",
          "bio": "科技自媒体",
          "followTime": "2026-09-22T10:00:00",
          "mutual": true
        }
      ]
    }
  }
  ```

### 粉丝列表：GET /api/users/{accountId}/followers

已登录用户，按时间倒序分页查询目标用户的粉丝列表。参数与响应结构同关注列表。

### 关系统计快照：GET /api/users/{accountId}/stats

已登录用户，查询用户的关注数与粉丝数。

- 成功响应：
  ```json
  {
    "code": 200,
    "message": "ok",
    "data": {
      "accountId": "u_1001",
      "followingCount": 42,
      "followerCount": 128
    }
  }
  ```

### 内部关注清单：GET /api/users/internal/{accountId}/following-ids

仅供内部微服务协同，返回目标用户关注的所有博主 ID 列表。规划由推荐服务 `FollowingRecallChannel` 调用；**当前推荐侧尚未接入**，关注召回恒为空。

## 4. 文件接口

所有文件接口都只操作当前普通用户自己的文件。新建 `fileId` 是无连字符的 32 位 ID，后续按返回值原样传递；未知 ID、他人文件、删除中和已删除记录在普通查询入口返回 `404`。

### 文件元数据字段

上传完成、文件查询和确认完成共用以下响应：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `fileId` | string | 文件 ID |
| `originName` | string | 展示文件名 |
| `mime` | string | 归一化的声明类型，不代表内容鉴定 |
| `declaredSize` | integer | 声明字节数 |
| `actualSize` | integer / null | 确认后的实际字节数 |
| `sha256` | string / null | 完成后记录的 SHA-256 摘要 |
| `status` | string | 文件业务状态：`ACTIVE/DISABLED` |
| `uploadStatus` | string | `PENDING/VERIFYING/COMPLETED/EXPIRED` |
| `uploadExpiresAt` | string / null | 直传业务确认截止时间，普通上传可为空 |
| `createdAt`, `updatedAt` | string | 创建与更新时间 |

文件时间字段对应 Java `Instant`，按 ISO-8601 时间点理解，不同于认证 `/verify` 的毫秒整数。`status` 与 `uploadStatus` 是两个维度：上传完成不代表资源一定允许下载。

### 普通上传：POST /api/files

发送 `multipart/form-data`：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `file` | 文件正文 | 是 | 非空单文件 |
| `storageType` | string | 否 | 当前可用实现为 `MINIO`，缺省取服务配置 |

展示名不能含 `/`、`\` 或控制字符，去首尾空白后最多 255 字符。默认应用大小上限为 20971520 字节；Servlet 默认文件上限 20MB、请求上限 21MB，部署可调整。未提供 MIME 时使用 `application/octet-stream`。

成功 HTTP `201`，返回文件元数据，`uploadStatus=COMPLETED`。参数不合法 `400`；超限 `413`；存储或保存结果未确认可能为 `503`，其他未预期数据库异常可能为 `500`。失败可能已经产生远端对象，不能假设重复上传会复用原文件 ID。

### 直传初始化：POST /api/files/direct-upload 与 POST /api/files/direct-upload/v2

| JSON 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `originName` | string | 是 | 规则同普通上传展示名 |
| `size` | integer | 是 | 正整数字节数，不超过应用大小上限 |
| `mime` | string | 否 | 缺省 `application/octet-stream`；归一化后最长 127 字符，不含控制字符 |
| `storageType` | string | 否 | 当前使用 `MINIO` |
| `sha256` | string | 仅 V2 必填 | 文件正文真实摘要，64 位小写十六进制 |

V1 最小示例：

```json
{"originName":"example.txt","size":5,"mime":"text/plain"}
```

V2 在相同请求中增加真实 `sha256`。**V2 新初始化已标记过期，不再作为新调用方的接入或迁移目标。** 当前阿里云 OSS 适配器未实现 V2 所需的 checksum PUT 签名，与现行存储策略不兼容；请使用现有 V1 直传或普通上传。代码中 V2 路由仍保留，默认关闭；只有 `file.direct-upload-v2.enabled=true` 才开放路由，启动校验还要求 `file.cleanup.enabled=true`，但仅开启开关并不能解决存储适配问题。不要用任意占位摘要发起实际上传。本文仅记录现有接口行为，未修改配置。

成功 HTTP `201`，返回：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `fileId` | string | 后续确认、查询使用的文件 ID |
| `uploadStatus` | string | 初始化为 `PENDING` |
| `putUrl` | string | 短期敏感上传地址 |
| `requiredHeaders` | object | PUT 必须原样携带的字符串 Header 键值对 |
| `putExpiresAt` | string | PUT 授权到期时间，默认 5 分钟 |
| `uploadExpiresAt` | string | 业务确认期限，默认 15 分钟 |

**初始化成功不等于文件上传完成。** 调用方随后向 `putUrl` 发送原始文件正文（不是再次发送 multipart），原样附带 `requiredHeaders`；V2 包括 `Content-Type` 与 `x-amz-checksum-sha256`。对象 PUT 的响应来自存储服务，不使用 `ApiResponse` 外壳。不得擅自修改签名地址或附加业务身份 Header。

PUT 成功后调用相应确认接口。无效参数 `400`；超限 `413`；V2 未启用 `404`；签名依赖失败可能为 `503`。初始化先保存记录再生成签名，失败可能已留下待上传记录；没有原记录重新签发 PUT 地址的 HTTP 接口。

### 确认：POST /api/files/{id}/confirm 与 POST /api/files/{id}/confirm/v2

无请求正文。存量记录按初始化版本选择对应接口；兼容 `/confirm` 也会将未完成的 V2 记录转交 V2 确认。V2 专用确认收到非 V2 记录返回 `409`。V2 新初始化标记过期且默认关闭，不代表已有 V2 记录不能确认；恢复与清理仍需保留。

| HTTP 状态 | `data` | 调用方动作 |
| --- | --- | --- |
| `200` | 完成文件元数据，`uploadStatus=COMPLETED` | 上传业务完成，可以申请下载地址 |
| `202` | `fileId`、`uploadStatus`、`pollAfterSeconds` | 仍在处理，按建议等待后查询或再次确认 |
| `503` | 错误响应；附带 `Retry-After: 2` | 至少等待 2 秒后重试，不能当作完成 |
| `410` | 错误响应 | 确认过期，重新初始化上传 |
| `404` | 错误响应 | 文件不可见，不继续轮询 |

受理示例（HTTP 为 `202`）：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "fileId": "00000000000000000000000000000001",
    "uploadStatus": "VERIFYING",
    "pollAfterSeconds": 2
  }
}
```

V1 受理时通常仍为 `PENDING`，V2 为 `VERIFYING`。异步失败不会回头修改之前的 `202`；需查询最新状态。V1 错误内容在截止前可能仍停留 `PENDING`；V2 暂存对象暂不可读可保留 `VERIFYING`，大小或条件复制不符则可转 `EXPIRED`。详见[直传流程与限制](modules/file.md)。

### 文件查询：GET /api/files/{id}

无正文，成功 `200` 返回文件元数据。`PENDING/VERIFYING/EXPIRED` 均可以在记录仍对本人可见时查询。GET 不读取对象正文、不自动确认，也不会重新签发上传地址。

### 下载地址：GET /api/files/{id}/download-url

无正文。成功 `200`，`data` 为 `url`（string）、`expiresAt`（ISO-8601 时间字符串），响应带 `Cache-Control: no-store`。默认地址有效期 2 分钟。

资源需为本人可见、正常且上传完成。未完成或禁用 `409`；正常资源的上传已过期 `410`；对象缺失或大小不符 `409`；存储不可用可能为 `503`。拿到地址后直接向对象存储 GET，返回文件正文，不是业务 JSON。

只能由本人申请不等于每次下载都重新鉴权；持有地址的人在其有效条件内可能访问对象。不要分享、长期保存该地址，也不要假设退出会立即撤销它。

### 删除：DELETE /api/files/{id}

无正文。对象删除和数据库完成状态明确后返回 `204`，没有 JSON 响应。重复删除本人已完成删除的记录也返回 `204`；未知或他人文件 `404`。

远端删除或数据库完成状态未确认可能为 `503`。此时记录可能已经进入删除中，并从查询、下载和确认入口隐藏；调用方仍可重复 DELETE 重试，不应把 GET 的 `404` 单独作为远端删除已完成的证明。当前无恢复文件接口。

## 5. 内容模块接口

内容模块（`content-service`）对外路由挂载于 `/api/content/**` 下。包含创作者工作台、前台公开点播、全站标签字典、管理端风控治理以及微服务内部协同回调。
**实体 ID 体系说明**：内部数据表与管理接口统一使用 32 位无连字符 UUID 全局主键（如 `{id}`）；对外公开点播、分享与流媒体检索统一使用 24 位高熵 Base62 业务短码（如 `{vid}`）。

### 5.1 创作者端接口

#### 创建视频草稿：POST /api/content/videos/draft

普通用户登录凭据（`requireUser`）。创建视频草稿实体并持久化，初始状态为 `status=ACTIVE`、`publishStatus=DRAFT`。

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `title` | string | 是 | 视频展示标题，非空，最多 128 字符 |
| `description` | string | 否 | 详细图文描述，最多 2000 字符 |
| `videoFileId` | string | 是 | 32 位文件 ID，指向 `file-service` 中已上传完毕的主视频资产 |
| `coverFileId` | string | 是 | 32 位文件 ID，指向 `file-service` 中已上传完毕的封面图片资产 |
| `duration` | integer | 是 | 视频时长（秒），非负整数 |
| `tags` | string | 否 | 逗号分隔的轻量标签文本（如 `Java,微服务`），最多 255 字符 |
| `visibility` | string | 否 | 可见性范围：`PUBLIC`（公开）、`PRIVATE`（私密）、`UNLISTED`（仅链接可见），默认 `PUBLIC` |

```json
{
  "title": "Spring Cloud 架构演进实践",
  "description": "本文讲解微服务架构演进历程与核心技术选型实践。",
  "videoFileId": "0123456789abcdef0123456789abcdef",
  "coverFileId": "abcdef0123456789abcdef0123456789",
  "duration": 600,
  "tags": "Java,微服务,架构",
  "visibility": "PUBLIC"
}
```

成功 HTTP `201 Created`，返回新生成的 24 位业务编码 `vid`：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "vid": "cv05hG9Kq2RtLw7XbPmZv4Ya"
  }
}
```

关键错误：未登录 `401`；标题或文件 ID 为空 `400`；时长小于 0 或字段超长 `400`。

#### 修改视频元数据：PUT /api/content/videos/{id}

作者本人或管理员。路径参数 `id` 为 32 位视频内部主键。全量更新视频图文元数据并重新全量对齐标签字典热度。处于 `AUDITING`（机审中）的视频禁止修改。

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `title` | string | 否 | 若提供则非空，最多 128 字符 |
| `description` | string | 否 | 最多 2000 字符 |
| `coverFileId` | string | 否 | 32 位文件 ID，需指向有效文件 |
| `tags` | string | 否 | 逗号分隔标签文本，全量覆盖旧标签并重新计算热度，最多 255 字符 |

```json
{
  "title": "Spring Cloud 架构演进实践（修订版）",
  "description": "更新了服务发现与动态配置章节内容。",
  "coverFileId": "abcdef0123456789abcdef0123456789",
  "tags": "Java,微服务,架构,SpringCloud"
}
```

成功 HTTP `200`，`data=null`。

关键错误：未登录 `401`；非本人且非管理员 `403`；视频不存在 `404`；处于 `AUDITING` 状态不可修改 `409`。

#### 提交审核流水线：POST /api/content/videos/{id}/submit

作者本人。路径参数 `id` 为 32 位视频主键。无请求正文。

业务前置强校验与调用流：
1. 校验当前生命周期必须为 `DRAFT`（草稿）或 `REJECTED`（审核驳回）；
2. 内部通过 OpenFeign 强探活 `file-service`（`GET /api/files/{id}`），验证 `videoFileId` 与 `coverFileId` 均为 `status=ACTIVE` 且 `uploadStatus=COMPLETED`，彻底杜绝空壳提审；
3. 本地事务原子更新生命周期状态为 `AUDITING`，初始化 5 类细分子任务，并在 `content_outbox` 写入 `content.video.submitted` 事件；
4. 数据库事务提交后虚拟线程毫秒级唤醒，投递至 RabbitMQ 交换机 `media.platform.events`，驱动下游机审与转码服务。

成功 HTTP `200`，`data=null`。

关键错误：未登录 `401`；非作者本人 `403`；视频不存在 `404`；主视频或封面文件未就绪 `400`；非草稿或驳回状态（如已在审核中或已发布）`409`。

#### 主动下架视频：POST /api/content/videos/{id}/offline

作者本人。路径参数 `id` 为 32 位主键。仅允许对已正式上线（`publishStatus = PUBLISHED`）的视频执行下架。

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `reason` | string | 否 | 下架原因说明，最多 255 字符 |

```json
{
  "reason": "作品内容更新，暂时下架重新剪辑"
}
```

成功 HTTP `200`，`data=null`。本地事务流转状态为 `OFFLINE`，写入 `content.video.offline` Outbox 事件（以事件类型作为路由键）。注意：推荐服务当前绑定的是 `content.video.offlined`，**下架事件到达不了推荐队列**，见 [推荐模块 · 已知问题 REC-01](modules/recommend.md#102-已知问题)。

关键错误：未登录 `401`；非本人 `403`；视频不存在 `404`；未上线视频 `409`。

#### 删除视频：DELETE /api/content/videos/{id}

作者本人。路径参数 `id` 为 32 位主键。无请求正文。
仅允许删除草稿（`DRAFT`）或已下架（`OFFLINE`）的视频；在线（`PUBLISHED`）或审核中（`AUDITING`）作品严禁直接删除。执行逻辑删除并级联释放关联标签引用热度。

成功 HTTP `204 No Content`，无响应正文。

关键错误：未登录 `401`；非本人 `403`；视频不存在 `404`；审核中或已发布视频不可删除 `409`。

#### 创作者作品列表：GET /api/content/videos/me

普通用户（创作者）。查询作者本人的所有视频作品列表，按创建时间倒序排列。

查询参数：
- `publishStatus`（string，可选）：按状态过滤（`DRAFT`, `AUDITING`, `PUBLISHED`, `REJECTED`, `OFFLINE`）
- `page`（integer，可选）：页码，从 1 起始，默认 1
- `size`（integer，可选）：每页大小，范围 1–100，默认 20

成功 HTTP `200`，返回 `CreatorPage`：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `records` | array | 视频项列表 |
| └ `id` | string | 32 位视频内部主键 |
| └ `vid` | string | 24 位公开业务短码 |
| └ `title` | string | 视频标题 |
| └ `coverFileId` | string | 封面图片资产 ID |
| └ `duration` | integer | 视频时长（秒） |
| └ `status` | string | 平台状态（`ACTIVE/DISABLED`） |
| └ `publishStatus` | string | 发布状态（`DRAFT/AUDITING/PUBLISHED/REJECTED/OFFLINE`） |
| └ `rejectReason` | string / null | 违规封禁或驳回说明（正常为空） |
| └ `publishedAt` | string / null | 正式上线时间（`LocalDateTime` 字符串） |
| └ `createdAt` | string | 创建时间 |
| └ `updatedAt` | string | 最后更新时间 |
| `total` | integer | 总记录数 |
| `page` | integer | 当前页码 |
| `size` | integer | 当前每页大小 |

关键错误：未登录 `401`；分页参数非法 `400`。

#### 查询发布流水线进度：GET /api/content/videos/{id}/tasks

作者本人或管理员。路径参数 `id` 为 32 位主键。无请求正文。
用于创作者发布后查看 5 类细分子任务（`AUDIT`, `TRANSCODE_720P`, `TRANSCODE_1080P`, `TRANSCODE_4K`, `VECTOR_EMBEDDING`）的执行状态与门禁放行判定指标。

成功 HTTP `200`，返回 `PipelineProgress`：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `videoId` | string | 视频主键 ID |
| `publishStatus` | string | 当前发布状态 |
| `eligibleForPublish` | boolean | 是否已达成放行门禁（机审通过 + 基准流就绪 + 向量就绪） |
| `tasks` | array | 子任务明细列表 |
| └ `id` | string | 任务主键 ID |
| └ `taskType` | string | 任务类型编码 |
| └ `taskName` | string | 任务展示中文名称（如“自动化安全机审”） |
| └ `status` | string | 任务状态（`PENDING/RUNNING/SUCCESS/FAILED/CANCELED`） |
| └ `progress` | integer | 执行进度百分比（0–100） |
| └ `retryCount` | integer | 已重试次数 |
| └ `errorMessage` | string / null | 失败错误原因 |
| └ `startedAt` | string / null | 开始执行时间 |
| └ `completedAt` | string / null | 完成时间 |

关键错误：未登录 `401`；非本人且非管理员 `403`；视频不存在 `404`。

---

### 5.2 前台点播与公共接口

#### 视频公开图文详情：GET /api/content/videos/{vid}

支持匿名游客、普通登录用户及管理员访问。路径参数 `vid` 为 24 位业务公开短码。

访问权限门禁规则：
- **匿名游客 / 普通登录用户**：仅可见 `status = ACTIVE`、`publishStatus = PUBLISHED` 且 `visibility = PUBLIC` 的已上线公开视频；
- **作者本人**：可直接查看名下的任何状态视频（包括草稿、审核中、私密视频）；
- **管理员**：放行全库视频检索（用于内容抽查与合规治理排查）；
- **未满足上述条件时**：统一按 `404 Not Found` 安全脱敏返回，杜绝外部探测私有资产存在性。

成功 HTTP `200`，返回 `Detail`：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `id` | string | 32 位视频内部主键 |
| `vid` | string | 24 位公开业务短码 |
| `authorId` | string | 创作者账号 ID |
| `title` | string | 视频标题 |
| `description` | string | 视频简介 |
| `coverFileId` | string | 封面文件资产 ID |
| `videoFileId` | string | 原始视频文件资产 ID |
| `duration` | integer | 视频时长（秒） |
| `tags` | array<string> | 关联标签列表（如 `["Java", "微服务"]`） |
| `status` | string | 平台治理状态（`ACTIVE/DISABLED`） |
| `publishStatus` | string | 发布状态（`PUBLISHED` 等） |
| `visibility` | string | 可见性范围（`PUBLIC/PRIVATE/UNLISTED`） |
| `viewCount` | integer | 播放计数快照 |
| `likeCount` | integer | 点赞计数快照 |
| `commentCount` | integer | 评论计数快照 |
| `starCount` | integer | 收藏计数快照 |
| `shareCount` | integer | 分享计数快照 |
| `publishedAt` | string / null | 正式发布时间 |
| `createdAt` | string | 创建时间 |

关键错误：视频不存在或无权访问脱敏 `404`；`vid` 格式不合法 `400`。

#### 查询可用转码播放流：GET /api/content/videos/{vid}/streams

支持匿名或已登录用户访问。路径参数 `vid` 为 24 位业务短码。权限门禁与详情接口严格一致。
仅返回压制就绪（`transcodeStatus = COMPLETED`）且流状态可用的切片列表，按清晰度从高到低排序（`4K > 1080P > 720P > 360P`）。客户端播放器按返回列表呈现清晰度切换菜单。

成功 HTTP `200`，返回 `PlayStreams`：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "vid": "cv05hG9Kq2RtLw7XbPmZv4Ya",
    "title": "Spring Cloud 架构演进实践",
    "streams": [
      {
        "quality": "1080P",
        "format": "MP4",
        "codec": "H264",
        "fileId": "stream_file_1080p_id_32char____",
        "fileSize": 52428800,
        "bitrate": 3500,
        "fps": 30
      },
      {
        "quality": "720P",
        "format": "MP4",
        "codec": "H264",
        "fileId": "stream_file_720p_id_32char_____",
        "fileSize": 26214400,
        "bitrate": 1800,
        "fps": 30
      }
    ]
  }
}
```

关键错误：视频不存在或无权访问 `404`。

#### 全站热门标签：GET /api/content/tags/hot

匿名公开接口，无须认证。用于创作者打标联想提示与前台热门分类发现。

查询参数：
- `limit`（integer，可选）：拉取条数，默认 20，上限 50。

成功 HTTP `200`，返回 `List<HotTag>`，按已发布视频引用热度降序排列：

```json
{
  "code": 200,
  "message": "ok",
  "data": [
    {
      "id": "tag_01",
      "name": "Java",
      "referenceCount": 128
    },
    {
      "id": "tag_02",
      "name": "Spring",
      "referenceCount": 96
    }
  ]
}
```

---

### 5.3 平台管理端视频治理接口

管理端端点统一挂载于 `/api/content/videos/admin/**` 下，强制校验管理员权限（`requireAdmin`），非管理员调用统一返回 `403 Forbidden`。

#### 管理端综合分页检索：POST /api/content/videos/admin/list

管理员。支持多条件复合过滤筛选全库视频。

查询参数：
- `page`（integer，可选）：页码，默认 1
- `size`（integer，可选）：每页条数，默认 20，上限 100

请求字段（JSON 正文，可选）：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `status` | string | 否 | 平台治理状态（`ACTIVE / DISABLED`） |
| `publishStatus` | string | 否 | 发布状态（`DRAFT / AUDITING / PUBLISHED / REJECTED / OFFLINE`） |
| `authorId` | string | 否 | 创作者账号 ID |
| `keyword` | string | 否 | 模糊匹配视频标题 `title` 或精确匹配公开编码 `vid` |

```json
{
  "status": "ACTIVE",
  "publishStatus": "PUBLISHED",
  "keyword": "架构"
}
```

成功 HTTP `200`，返回 `AdminPage`：每项包含 `id, vid, authorId, title, coverFileId, status, publishStatus, rejectReason, publishedAt, createdAt` 及分页信息。

关键错误：非管理员 `403`；未登录 `401`；分页范围非法 `400`。

#### 违规封禁视频：POST /api/content/videos/admin/{id}/ban

管理员。路径参数 `id` 为 32 位主键。将视频强制下线并列入风控黑名单。

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `reason` | string | 是 | 违规封禁原因说明，非空，最多 255 字符 |

```json
{
  "reason": "包含未授权涉政违规内容"
}
```

业务处理：
- 置位 `status = DISABLED`，记录封禁原因；
- 本地事务写入 `content_outbox` 待发布事件（`content.video.banned`）；
- 驱动快速通道通知推荐流与搜索引擎立即抹除该视频索引，端侧播放长连接实时截流。

成功 HTTP `200`，`data=null`。

关键错误：原因说明为空 `400`；非管理员 `403`；视频不存在 `404`。

#### 解除视频封禁：POST /api/content/videos/admin/{id}/unban

管理员。路径参数 `id` 为 32 位主键。无请求正文。
将此前被误封禁的视频恢复为 `status = ACTIVE`，本地事务写入 `content.video.unbanned` Outbox 领域事件，广播各下游系统恢复可见性。

成功 HTTP `200`，`data=null`。

关键错误：非管理员 `403`；视频不存在 `404`。

---

### 5.4 微服务内部协同与回调接口

端点挂载于 `/api/content/videos/internal/**` 下。专供同网段内部受信微服务（`audit-service`、`transcode-service` 与分布式 Worker）回调通知，网关禁止公网未受信任客户端直接访问。

#### 接收机审判定结果回调：POST /api/content/videos/internal/audit-callback

调用方：`audit-service`。机审流水线判定完成后回调此接口。业务层实现强幂等处理。

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `videoId` | string | 是 | 32 位视频内部主键 |
| `passed` | boolean | 是 | 审核是否通过（`true`=合规放行，`false`=违规驳回） |
| `rejectReason` | string | 否 | 当 `passed=false` 时的具体驳回说明 |

```json
{
  "videoId": "0123456789abcdef0123456789abcdef",
  "passed": true,
  "rejectReason": null
}
```

业务处理：
- 更新 `video_task` 表中 `AUDIT` 任务状态为 `SUCCESS` 或 `FAILED`；
- 调用 `PublishGatekeeper` 门禁决策核心：
  - 若 `passed = true`：核查基准转码流（720P/1080P）与向量特征是否均已就绪；若全部达成，状态原子跃迁为 `PUBLISHED` 并写 Outbox 广播上线事件；
  - 若 `passed = false`：状态立即流转为 `REJECTED`，记录驳回原因，级联取消其余在途子任务，写 Outbox 广播驳回事件。

成功 HTTP `200`，`data=null`。

关键错误：参数非法 `400`；视频不存在 `404`。

#### 接收转码产物切片注册回调：POST /api/content/videos/internal/transcode-callback

调用方：`transcode-service`。压制完成并托管上传切片至 `file-service` 后回调此接口。

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `videoId` | string | 是 | 32 位视频主键 |
| `quality` | string | 是 | 转码画质清晰度：`360P / 720P / 1080P / 4K / RAW` |
| `fileId` | string | 是 | 转码产物在 `file-service` 中的资产 ID |
| `format` | string | 否 | 流媒体封装格式，默认 `MP4` |
| `codec` | string | 否 | 编码标准，默认 `H264` |
| `fileSize` | integer | 否 | 切片物理字节大小 |
| `bitrate` | integer | 否 | 码率（kbps） |
| `fps` | integer | 否 | 帧率（fps） |
| `duration` | integer | 否 | FFprobe 探测校准后的真实视频时长（秒） |
| `transcodeStatus` | string | 否 | 转码状态，默认 `COMPLETED` |

```json
{
  "videoId": "0123456789abcdef0123456789abcdef",
  "quality": "1080P",
  "fileId": "f_1080p_transcoded_asset_0000001",
  "format": "MP4",
  "codec": "H264",
  "fileSize": 52428800,
  "bitrate": 3500,
  "fps": 30,
  "duration": 600,
  "transcodeStatus": "COMPLETED"
}
```

业务处理：
- 幂等持久化/更新 `video_stream` 流规格表；
- 若上报了 `duration`，同步校准视频聚合根时长；
- 更新对应画质的 `video_task` 状态为 `SUCCESS`；
- 触发 `PublishGatekeeper` 门禁重新核查，若满足上线条件即自动放行。

成功 HTTP `200`，`data=null`。

关键错误：参数校验失败 `400`；视频不存在 `404`。

#### 接收通用任务进度汇报：POST /api/content/videos/internal/task-callback

调用方：外部通用 Worker（如 AI 向量提取服务）。用于更新长耗时任务进度（0–100%）或汇报完成/失败。

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `videoId` | string | 是 | 32 位视频主键 |
| `taskType` | string | 是 | 任务类型：`AUDIT / TRANSCODE_720P / TRANSCODE_1080P / TRANSCODE_4K / VECTOR_EMBEDDING` |
| `status` | string | 是 | `RUNNING / SUCCESS / FAILED` |
| `progress` | integer | 否 | 进度百分比，0–100 |
| `errorMessage` | string | 否 | 失败时的具体异常信息 |

```json
{
  "videoId": "0123456789abcdef0123456789abcdef",
  "taskType": "VECTOR_EMBEDDING",
  "status": "SUCCESS",
  "progress": 100,
  "errorMessage": null
}
```

业务处理：
- 更新 `video_task` 表中对应类型的状态与进度；
- 若任务标记为 `SUCCESS`，就地触发 `PublishGatekeeper` 门禁评估；
- 若阻断性任务报告 `FAILED`，触发超时与失败熔断。

成功 HTTP `200`，`data=null`。

关键错误：参数非法 `400`；任务不存在 `404`。

---

## 6. 审核模块接口

审核模块（`audit-service`）对外路由挂载于 `/api/audit/**` 下。主要包括管理后台人工复审工作台、外部云厂商（阿里云内容安全 2.0）异步回调 Webhook 以及内部调试与任务证据查询接口。

### 6.1 管理后台人工复审接口

管理端端点统一挂载于 `/api/audit/admin/**` 下，需具备管理员角色权限（`requireAdmin`），网关自动透传 `X-User-Id` 与 `X-User-Role`。

#### 管理端分页检索工单：GET /api/audit/admin/tasks

管理员。用于风控运营后台按多维度条件组合筛选审核工单。

查询参数：
- `stage`（string，可选）：审核阶段（`RECEIVED` 待处理、`MACHINE_AUDITING` 机审中、`MANUAL_PENDING` 待人审、`FINISHED` 已终审）
- `result`（string，可选）：审核裁决结论（`PENDING` 处理中、`PASSED` 通过、`REJECTED` 驳回）
- `reviewLevel`（string，可选）：风险等级（`NORMAL` 正常合规、`SUSPICIOUS` 存疑待人工审、`ILLEGAL` 明确违规）
- `bizId`（string，可选）：业务实体 ID（如视频主键 `videoId`）
- `page`（integer，可选）：页码，从 1 起始，默认 1
- `size`（integer，可选）：每页条数，默认 20，上限 100

成功 HTTP `200`，返回 `TaskPage`：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `records` | array | 审核任务项列表 |
| └ `id` | string | 32 位工单主键 ID |
| └ `taskNo` | string | 业务流水号（如 `aud_0123...`） |
| └ `bizType` | string | 业务类型（`VIDEO`） |
| └ `bizId` | string | 业务主键 ID（如 `videoId`） |
| └ `bizVid` | string / null | 业务公开短码（如 `vid`） |
| └ `authorId` | string | 创作者账号 ID |
| └ `titleSnapshot` | string | 送审时标题快照 |
| └ `stage` | string | 当前审核阶段 |
| └ `result` | string | 裁决结果 |
| └ `reviewLevel` | string | 风险级别 |
| └ `rejectReason` | string / null | 驳回原因说明 |
| └ `operatorId` | string | 终审操作人（`SYSTEM` 或管理员 ID） |
| └ `callbackStatus` | string | 回调内容服务状态（`PENDING/SUCCESS/FAILED`） |
| └ `createdAt` | string | 工单创建时间（ISO-8601） |
| └ `updatedAt` | string | 工单更新时间 |
| `total` | integer | 总工单数 |
| `page` | integer | 当前页码 |
| `size` | integer | 当前每页大小 |

关键错误：未登录 `401`；非管理员 `403`；参数非法 `400`。

#### 查询工单全景与机审证据：GET /api/audit/admin/tasks/{id}

管理员。路径参数 `id` 为 32 位审核工单主键。返回主任务信息以及文本 DFA、封面图片、音视频多维度的原始机审判定证据链。

成功 HTTP `200`，返回 `TaskDetail`：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `task` | object | 主任务基本信息（同上述 `TaskItem` 结构） |
| `coverFileId` | string | 送审封面图片资产 ID |
| `videoFileId` | string | 送审主视频文件资产 ID |
| `descriptionSnapshot` | string / null | 送审简介文本快照 |
| `details` | array | 多维度审查证据明细列表 |
| └ `id` | string | 明细主键 ID |
| └ `dimension` | string | 审查维度：`TEXT`（文本）、`IMAGE`（封面图片）、`VIDEO`（音视频） |
| └ `engineType` | string | 判审引擎类型（如 `LOCAL_DFA`、`RULE_IMAGE`、`ALIYUN_GREEN`） |
| └ `level` | string | 单项判定风险级别：`NORMAL / SUSPICIOUS / ILLEGAL` |
| └ `confidence` | number | 判定置信度分值（0.00–100.00） |
| └ `hitWords` | string / null | 命中的违规词条、分类或特征标签（逗号分隔） |
| └ `detailLog` | string / null | 引擎原始判定明细或命中位置日志 |
| └ `createdAt` | string | 明细记录时间戳 |

关键错误：非管理员 `403`；工单不存在 `404`。

#### 管理员人工复审裁决：POST /api/audit/admin/tasks/{id}/review

管理员。对命中存疑（`SUSPICIOUS`）推入人工待审队列的工单执行最终审批。
**联动业务闭环**：裁决完成后，自动通过 OpenFeign 内部端点（`POST /api/content/videos/internal/audit-callback`）回调内容微服务推进门禁流转；若网络超时，自动交由 `AuditCallbackRetryScheduler` 进行指数退避重试。

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `action` | string | 是 | 复审操作动作：`APPROVE`（放行通过）或 `REJECT`（违规驳回） |
| `reason` | string | 驳回必填 | 驳回时的具体原因说明；通过时可选 |

```json
{
  "action": "REJECT",
  "reason": "视频封面包含低俗违规引流水印"
}
```

成功 HTTP `200`，返回更新后的工单全景详情 `TaskDetail`（`stage=FINISHED`，`result=PASSED` 或 `REJECTED`）。

关键错误：`action` 非法 `400`；工单不存在 `404`；工单已处于终态不可重复复审 `409`。

---

### 6.2 云厂商异步回调接口

#### 接收阿里云视频机审异步通知：POST /api/audit/callback/aliyun/video

调用方：阿里云内容安全 2.0 服务端。
**鉴权机制**：网关白名单放行免 Bearer Token，由服务端执行严格的 SHA-256 Checksum 防篡改签名验签。

请求参数（URL Query 或 Form 格式）：
- `checksum`（string，必填）：阿里云计算签发的总和校验码
- `content`（string，必填）：包含任务 ID、风险标签与违规分值的原始 JSON 字符串

成功响应 HTTP `200`（按阿里云回调契约返回标准 JSON）：

```json
{
  "code": 200,
  "msg": "success"
}
```

业务处理：
- 验签通过后提取阿里云任务判定结果；
- 关联并持久化工单 `VIDEO` 维度明细证据；
- 触发 `AuditDecisionAggregator` 综合仲裁引擎重新评估三维总风险。

关键错误：签名校验失败 `400` 或 `403`。

---

### 6.3 内部调试与协同接口

端点挂载于 `/api/audit/internal/**` 或 `/api/audit/tasks/**`，生产环境由网关拦截仅供微服务内网使用。

#### 内部模拟提审触发机审流水线：POST /api/audit/internal/submit

内部测试与端到端演练工具。绕过 MQ 事件监听直接由参数装配并发起全量机审流水线。

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `videoId` | string | 是 | 32 位关联视频内部主键 |
| `vid` | string | 否 | 业务公开编码（如 `cv05hG9K...`） |
| `authorId` | string | 是 | 创作者账号 ID |
| `title` | string | 否 | 送审标题快照 |
| `description` | string | 否 | 送审简介快照 |
| `coverFileId` | string | 是 | 封面图片资产 ID |
| `videoFileId` | string | 是 | 原视频文件资产 ID |

成功 HTTP `200`，返回初始化的工单项 `TaskItem`（初始阶段 `MACHINE_AUDITING`）。

#### 内部查询任务证据明细：GET /api/audit/tasks/{id}

内部微服务或排查工具按工单主键 ID 查询完整判定证据（返回 `TaskDetail`）。

---

## 7. 转码模块接口

转码模块（`transcode-service`）对外路由挂载于 `/api/transcode/**` 下。主要面向异步计算与工单追踪。

### 7.1 转码调度与查询接口

#### 按工单 ID 查询转码任务详情：GET /api/transcode/tasks/{id}

支持管理员或内部微服务排查。路径参数 `id` 为 32 位转码工单 ID。

成功 HTTP `200`，返回 `TranscodeTask` 任务工单对象：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `id` | string | 32 位工单主键 |
| `videoId` | string | 关联的视频内部全局主键 ID |
| `authorId` | string | 创作者账号 ID |
| `sourceFileId` | string | 待转码原片文件资产 ID |
| `targetQuality` | string | 目标画质预设（`P360`, `P720`, `P1080`, `P4K`） |
| `targetFormat` | string | 封装格式（`MP4`, `HLS`, `DASH`） |
| `targetCodec` | string | 视频编码（`H264`, `H265`, `AV1`） |
| `status` | string | 工单流转状态（`PENDING / DOWNLOADING / TRANSCODING / UPLOADING / NOTIFYING / COMPLETED / FAILED`） |
| `retryCount` | integer | 已重试次数 |
| `maxRetries` | integer | 最大重试上限（默认 3） |
| `outputFileId` | string / null | 切片产物在 `file-service` 中注册的文件资产 ID |
| `outputFileSize` | integer / null | 产物物理字节大小 |
| `outputBitrate` | integer / null | 实际输出码率（kbps） |
| `outputFps` | integer / null | 实际输出帧率（fps） |
| `outputWidth` | integer / null | 视频输出宽度像素 |
| `outputHeight` | integer / null | 视频输出高度像素 |
| `videoDuration` | integer / null | FFprobe 探测出的视频真实时长（秒） |
| `transcodeCostMs` | integer / null | FFmpeg 纯压制耗时（毫秒） |
| `totalCostMs` | integer / null | 全流程端到端总耗时（毫秒） |
| `errorMessage` | string / null | 失败原因说明 |
| `createdAt`, `updatedAt` | string | 创建与更新时间戳 |

关键错误：工单不存在 `404`。

#### 手动触发指定画质切片压制：POST /api/transcode/tasks/trigger

管理员或内部调试工具。用于针对指定原片重新发起单个画质规格的切片压制（例如 4K 补转或重试已失败任务）。

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `videoId` | string | 是 | 32 位视频内部主键 |
| `authorId` | string | 是 | 创作者账号 ID |
| `sourceFileId` | string | 是 | 原始文件资产 ID |
| `quality` | string | 否 | 目标画质规格编码（`360P / 720P / 1080P / 4K`，缺省默认 `720P`） |

```json
{
  "videoId": "0123456789abcdef0123456789abcdef",
  "authorId": "user_000000000000000000000000001",
  "sourceFileId": "f_raw_video_file_00000000000001",
  "quality": "1080P"
}
```

业务处理：
- 解析画质规格预设（`QualityPreset`）；
- 创建并持久化 `TranscodeTask` 工单；
- 调度执行流水线：下载原片 ➔ FFmpeg 并发受控压制 ➔ 探测媒体参数 ➔ 托管上传至 `file-service` ➔ Feign 回调 `content-service` 登记并触发门禁。

成功 HTTP `200`，返回新创建的 `TranscodeTask` 工单实体。

关键错误：必填参数为空 `400`；源文件不存在或拉流失败 `404` 或 `503`。

---

## 8. 互动模块接口

路径前缀 `/api/interactions`，由 `interaction-service` 提供。设计说明见 [互动模块](modules/interaction.md)。

### 8.1 点赞：POST / DELETE /api/interactions/videos/{vid}/like

- 无请求体。重复点赞或重复取消都是幂等的。
- 响应 `data`：`{ "vid": "...", "action": "LIKE" | "UNLIKE", "active": true | false }`。

### 8.2 收藏：POST / DELETE /api/interactions/videos/{vid}/star

- POST 请求体可省略：`{ "folderId": "可选，省略则进默认收藏夹" }`。
- DELETE 查询参数 `folderId` 可选，省略则从全部收藏夹移除。
- 响应 `data`：`{ "vid", "action": "STAR" | "UNSTAR", "active" }`。
- 指定的收藏夹不存在或已删除时返回 `400`。

### 8.3 收藏夹：GET / POST /api/interactions/star/folders

- GET 返回收藏夹数组；用户一个收藏夹都没有时会自动创建默认收藏夹。
- POST 请求体 `{ "title": "必填" }`，`title` 为空时返回 `400`。
- 元素字段：`id`、`title`、`isDefault`、`status`、`createdAt`。

### 8.4 收藏明细：GET /api/interactions/star/items

- 查询参数：`folderId`（可选，默认收藏夹）、`page`（默认 1）、`size`（默认 20，最大 100）。
- 元素字段：`id`、`folderId`、`vid`、`createdAt`。

### 8.5 播放心跳：POST /api/interactions/videos/{vid}/heartbeat

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `position` | int | 当前播放头（秒），服务端截到 `[0, videoDuration]` |
| `deltaDuration` | int | 距离上次心跳实际观看的秒数，服务端截到 `[0, 15]` |
| `videoDuration` | int | 视频总时长（秒），0 表示未知 |

- 响应 `data`：`{ vid, lastPosition, watchedDuration, videoDuration, completed }`。
- 有效播放、完播判定和计数规则见 [互动模块 §3](modules/interaction.md#3-观看心跳与播放资格)。
- 服务端等锁超时时，只返回已有进度，本次时长不入账。

### 8.6 断点进度：GET /api/interactions/videos/{vid}/watch-progress

- 响应结构同 8.5；没有记录或游客时返回零进度。

### 8.7 观看历史：GET / DELETE /api/interactions/watch/history

- GET 查询参数 `page`（默认 1）、`size`（默认 20，最大 100）；元素字段 `id`、`vid`、`lastPosition`、`watchedDuration`、`videoDuration`、`completed`、`firstWatchAt`、`lastWatchAt`。
- DELETE 带 `vid` 删除单条，不带则清空；都是逻辑删除，保留防刷依据，不会重置播放冷却。

### 8.8 播放页快照：GET /api/interactions/videos/{vid}/my-state

- 响应 `data`：`{ vid, liked, starred, lastWatchPosition, completed }`；游客全部返回默认值。

### 8.9 公开计数：GET /api/interactions/videos/{vid}/stat 与 POST /api/interactions/videos/stats

- 单条响应：`{ vid, viewCount, likeCount, starCount, shareCount, commentCount }`。
- 批量请求体 `{ "vids": ["..."] }`，响应是以 `vid` 为键的对象；缺失的视频补 0。
- 计数经过 Redis 写缓冲，属于最终一致，可能比明细滞后几秒。

### 8.10 分享：POST /api/interactions/videos/{vid}/share

- 必须带 Header `Idempotency-Key`，缺失时返回 `400`。
- 同一个键重复请求：幂等成功，不重复计数。同一个键被别的用户或别的视频用过：当前返回 `500`（已知问题，计划改为 `409`）。
- 响应 `data`：`{ vid, action: "SHARE", active: true }`。

## 9. 推荐模块接口

推荐模块（`recommend-service`）挂载于 `/api/recommend/**`，只返回推荐决策（视频短码），详情由客户端向内容服务获取。实现细节与已知问题见 [推荐模块](modules/recommend.md)。

- 经网关访问全部需要令牌；服务内“允许匿名”的分支只在直连服务时生效。
- 参数非法（未知 `actionType` / `blockType`）或屏蔽接口缺少身份时抛出 `IllegalArgumentException`，服务无统一异常映射，**推断返回 `500`**（未实测）。

### 首页推荐流：GET /api/recommend/feed

- Query：`size`，默认 10，上限 50。
- 登录用户优先从 Redis 待看队列弹出，队列为空时现场生成；重复调用即取下一批，**没有游标**。
- 响应 `data`：`{ items: [{ vid, recallChannel, score, reason }], hasMore }`。
- `recallChannel`：`PERSONALIZED` / `EXPLORE_SIMILAR` / `EXPLORE_RANDOM` / `TRENDING` / `COLD_START`（`FOLLOWING` 当前不会出现）。

### 行为反馈：POST /api/recommend/feedback

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `vid` | string | 是 | 视频短码 |
| `actionType` | string | 是 | `IMPRESSION` / `PLAY` / `SKIP` / `DISLIKE` |
| `playDuration` | int | 否 | 实际播放秒数 |
| `videoDuration` | int | 否 | 视频总秒数 |
| `reason` | string | 否 | `DISLIKE_AUTHOR` 屏蔽作者，其余按屏蔽视频处理 |
| `occurredAt` | datetime | 否 | 行为发生时间 |

成功 `200`，`data=null`。游客只记流水，不更新画像。

### 推荐屏蔽：/api/recommend/blocks

- `POST`：请求体 `{ blockType: VIDEO|AUTHOR|TOPIC, targetId, reason? }`，返回屏蔽记录 `{ id, userId, blockType, targetId, reason, createdAt }`。
- `DELETE`：Query `blockType`、`targetId`，成功 `data=null`。
- `GET`：返回当前用户全部屏蔽记录列表。

## 10. 核对来源与验证边界

本文核对了当前各微服务控制器源码及关联契约组件：
- **认证服务**：[AuthController](../service/auth-service/src/main/java/com/calles/platform/auth/interfaces/http/AuthController.java)
- **用户服务**：
  - 前台个人与公开资料：[UserProfileController](../service/user-service/src/main/java/com/calles/platform/user/interfaces/http/profile/UserProfileController.java)
  - 社交关系与关注粉丝：[UserFollowController](../service/user-service/src/main/java/com/calles/platform/user/interfaces/http/follow/UserFollowController.java)
  - 管理端用户资料治理：[AdminUserProfileController](../service/user-service/src/main/java/com/calles/platform/user/interfaces/http/profile/AdminUserProfileController.java)
  - 内部协同关注调用端点：[UserFollowInternalController](../service/user-service/src/main/java/com/calles/platform/user/interfaces/http/follow/UserFollowInternalController.java)
- **文件服务**：[FileController](../service/file-service/src/main/java/com/calles/platform/file/interfaces/http/FileController.java)
- **内容服务**：
  - 创作者端：[CreatorVideoController](../service/content-service/src/main/java/com/calles/platform/content/interfaces/http/video/CreatorVideoController.java)
  - 前台点播：[PortalVideoController](../service/content-service/src/main/java/com/calles/platform/content/interfaces/http/video/PortalVideoController.java)
  - 标签字典：[TagController](../service/content-service/src/main/java/com/calles/platform/content/interfaces/http/tag/TagController.java)
  - 管理后台：[AdminVideoController](../service/content-service/src/main/java/com/calles/platform/content/interfaces/http/video/AdminVideoController.java)
  - 内部协同：[InternalVideoController](../service/content-service/src/main/java/com/calles/platform/content/interfaces/http/video/InternalVideoController.java)
- **审核服务**：
  - 管理端复审：[AdminAuditController](../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/http/controller/admin/AdminAuditController.java)
  - 阿里云回调：[AliyunAuditCallbackController](../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/http/controller/callback/AliyunAuditCallbackController.java)
  - 内部端点：[InternalAuditController](../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/http/controller/internal/InternalAuditController.java)
- **转码服务**：
  - 任务调度与查询：[TranscodeTaskController](../service/transcode-service/src/main/java/com/calles/platform/transcode/interfaces/http/TranscodeTaskController.java)
- **互动服务**：[InteractionLikeController](../service/interaction-service/src/main/java/com/calles/platform/interaction/interfaces/http/InteractionLikeController.java)、[InteractionStarController](../service/interaction-service/src/main/java/com/calles/platform/interaction/interfaces/http/InteractionStarController.java)、[InteractionWatchController](../service/interaction-service/src/main/java/com/calles/platform/interaction/interfaces/http/InteractionWatchController.java)、[InteractionStatController](../service/interaction-service/src/main/java/com/calles/platform/interaction/interfaces/http/InteractionStatController.java)
- **推荐服务**：[RecommendFeedController](../service/recommend-service/src/main/java/com/calles/platform/recommend/interfaces/web/RecommendFeedController.java)
- **网关路由与安全配置**：[gateway-service/application.yml](../service/gateway-service/src/main/resources/application.yml) 与 [gateway-application.yml](back/gateway-application.yml)

本文基于当前最新代码与接口层契约整理。具体用例、状态流转与时序图见各模块专用设计文档。
