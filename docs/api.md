# HTTP 接口文档

按当前代码整理，更新于 2026-09-09。本文供接口调用时查询请求、响应、权限和关键限制；实现流程分别见[认证](modules/auth.md)、[用户](modules/user.md)、[文件](modules/file.md)文档。代码已存在不表示接口已在真实环境启用或验收。

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
| POST | `/api/files` | 普通用户 | 普通上传 |
| POST | `/api/files/direct-upload` | 普通用户 | V1 直传初始化 |
| POST | `/api/files/direct-upload/v2` | 普通用户；默认关闭 | V2 直传初始化 |
| POST | `/api/files/{id}/confirm` | 普通用户 | V1 兼容确认 |
| POST | `/api/files/{id}/confirm/v2` | 普通用户 | V2 确认 |
| GET | `/api/files/{id}` | 普通用户 | 本人文件元数据 |
| GET | `/api/files/{id}/download-url` | 普通用户 | 申请短期下载地址 |
| DELETE | `/api/files/{id}` | 普通用户 | 删除本人文件 |

内容、审核、互动和推荐只有预留路由，尚无业务接口。内部资料补齐、文件清理和进程内内容读取也没有 HTTP 入口，不在这里编造地址。网关另配置 `/actuator/health`、`/actuator/info` 白名单，属于管理探针，不代表所有下游管理接口都对外开放。

## 2. 认证接口

### 注册：POST /api/auth/register

请求字段：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `loginName` | string | 是 | 3–255 个字母、数字、下划线或连字符 |
| `password` | string | 是 | 至少 8 个字符，最多 72 个 UTF-8 字节；HTTP 字符长度上限也为 72 |

```json
{
  "loginName": "example_user",
  "password": "<符合长度要求的测试密码>"
}
```

成功 `200`：返回 `accountId`、`loginName`、`role`、`status`，均为字符串；新账号固定 `USER / ACTIVE`。账号 ID 是无连字符的 32 位十六进制字符串。**不返回令牌，不自动登录，资料异步初始化。**

关键错误：输入校验失败 `400`；预先查到登录名占用 `409`。并发重名可能触发数据库重复键异常并走通用 `500`，当前未统一映射为 `409`。

### 登录：POST /api/auth/login

JSON 必填 `loginName`、`password`。登录名非空、最多 255 字符，应用层去首尾空白；密码非空，HTTP 最多 72 字符且应用层复核不超过 72 个 UTF-8 字节。请求形状与注册相同。

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

无查询参数。成功 `200` 返回：`accountId`、`loginName`、`role`（`USER/ADMIN`）、`type`（`user/admin`）、`sessionId`，均为字符串。服务读取最新账号数据，但响应**没有 `status` 字段**，也不包含昵称、简介等用户资料。

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

V2 在相同请求中增加真实 `sha256`。不要用任意占位摘要发起实际上传。V2 新初始化默认关闭，只有 `file.direct-upload-v2.enabled=true` 才开放；启动校验还要求 `file.cleanup.enabled=true`。本次未变更这些配置。

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

无请求正文。通常按初始化版本选择对应接口；兼容 `/confirm` 也会将未完成的 V2 记录转交 V2 确认。V2 专用确认收到非 V2 记录返回 `409`。关闭 V2 新初始化不代表已有 V2 记录不能确认。

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

## 5. 核对来源与验证边界

本文核对了当前的 [AuthController](../service/auth-service/src/main/java/com/calles/platform/auth/interfaces/http/AuthController.java)、[UserProfileController](../service/user-service/src/main/java/com/calles/platform/user/interfaces/http/UserProfileController.java)、[FileController](../service/file-service/src/main/java/com/calles/platform/file/interfaces/http/FileController.java)，以及关联请求/响应 DTO、权限策略、异常处理和[网关配置](../service/gateway-service/src/main/resources/application.yml)。具体用例和测试入口见各模块文档。

本次仅执行接口声明覆盖核对、文档链接、Markdown 格式和 JSON 示例语法检查，未发送真实 HTTP 请求，未执行编译、业务测试或外部依赖联调。本文是当前源码行为参考，不是运行环境接口验收报告。
