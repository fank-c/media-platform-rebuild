# Auth HTTP API v1

> 本次文档整理补录工作区已存在但原契约遗漏的 register、verify、me 接口；未修改业务代码、
> 路由或鉴权。原 v1 生效日期保留，补录核对日期为 2026-09-05，不代表新增接口已独立验收。

## 基本信息

- 所有者：`auth-service`
- 调用方：客户端、`gateway-service`
- 生效日期：2026-09-03
- 生效范围：新认证闭环；不兼容旧单体 `adminToken` 和旧 MD5 Token
- 当前版本：`v1`
- 数据所有权：`auth_account` 仅由 `auth-service` 读写

所有接口使用统一响应结构：

```json
{
  "code": 200,
  "message": "ok",
  "data": {}
}
```

## `GET /api/auth/ping`

连通性探针，无需鉴权，不签发凭据。

## `POST /api/auth/register`（现有实现补录）

匿名注册，只同步创建普通认证账户，不直接写入用户资料，也不签发 Token。账号写入与
`auth.account.created.v1` 的 Outbox 记录位于同一数据库事务；提交后由 user-service 异步初始化资料，
RabbitMQ 暂时不可用不会改变本接口成功响应，但会使资料进入可观测的延迟初始化状态。请求为 JSON：

| 字段 | 类型 | 必填与校验 |
| --- | --- | --- |
| loginName | string | 必填，3–255 字符，仅字母、数字、下划线、连字符 |
| password | string | 必填，8–72 字符，应用层还限制不超过 72 UTF-8 字节；敏感值 |

成功返回 HTTP 200，`data` 包含字符串字段 `accountId`（32 位无连字符 UUID）、
`loginName`、`role`（固定 USER）、`status`（固定 ACTIVE）。
已存在登录名的顺序注册返回 409；并发唯一键异常映射尚需测试，不承诺所有竞争场景均返回 409。
登录名比较受实际数据库排序规则约束，不能按旧代码注释承诺大小写敏感。
注册不是幂等创建接口，客户端不可无条件自动重试。
调用方不得把注册成功等同于资料已完成初始化；本人资料接口会在真正缺失时返回 `PENDING`，首次 PATCH
可按用户资料 v1 契约完成兜底建档。

## `POST /api/auth/login`

无需鉴权。请求体：

```json
{
  "loginName": "demo",
  "password": "<客户端密码>"
}
```

`loginName`、`password` 均为必填非空字符串；名称最长 255 字符，密码还受 72 UTF-8 字节限制。
服务端按 `login_name` 查询账户，使用 BCrypt 校验密码；账户必须为 `ACTIVE`。
账号不存在和正常账户密码错误返回 `401`；当前实现先判断禁用状态，因此禁用账户返回 `403`。

成功响应：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "accessToken": "<JWT>",
    "refreshToken": "<opaque-random-value>",
    "expiresIn": 900,
    "role": "USER"
  }
}
```

`expiresIn` 单位为秒，表示 Access Token 剩余有效期。Refresh Token 不设置为 JWT，服务端只保存其 SHA-256 哈希。

## `POST /api/auth/refresh`

无需 Access Token。请求体：

```json
{
  "refreshToken": "<原始刷新凭据>"
}
```

Refresh Token 必须存在、未过期且未被消费。服务先原子消费旧索引并保留带 in-flight 标记的原会话，
再读取 `auth_account` 的当前状态和角色；只有仍由同一刷新请求持有该会话时，才返回新的 Access/Refresh Token 对。
因此 logout 与 refresh 并发时，logout 在完成写入前删除会话将使 refresh 返回失败，不能重建已注销的 `sid`。
旧 Refresh Token 一旦被成功消费，即使账户校验、候选签发或 Redis 完成写入失败也不会恢复。

无效、过期、已注销、已轮换或完成前丢失会话归属的 Refresh Token 返回 `401`；账户已禁用返回 `403`。
Redis 会话读写失败、脚本结构异常或完成结果未知返回 `503`，不返回候选 Token。

## `POST /api/auth/logout`

要求请求头：

```http
Authorization: Bearer <access-token>
```

服务端验证 Access Token 后，删除其 `sid` 对应的刷新会话，并将当前 `jti` 写入失效列表，直到该 Access Token 自然过期。重复注销保持状态副作用幂等；缺少或无效的 Access Token 返回 `401`。
网关可能在重复请求到达认证服务前因已撤销 Token 返回 401，不能承诺重复注销始终返回 200。
仅撤销当前 `jti`，不据此承诺同会话所有历史访问令牌同时失效；缓存风险见认证设计。

## `POST /api/auth/verify`（现有实现补录）

意图调用方是 gateway-service；尚未确认其他实际消费者。认证服务接收必填非空字符串字段
`token`，可带或不带 `Bearer ` 前缀。网关内部调用直接访问认证服务，超时 3 秒、无显式重试；
调用失败转换为无效结果，最终向客户端返回 401。

成功调用的 HTTP 为 200，`data` 字段如下：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| valid | boolean | 是否验证有效 |
| subject | string/null | 认证账户 ID |
| type | string/null | user 或 admin |
| role | string/null | USER 或 ADMIN |
| sessionId | string/null | 刷新会话 ID |
| expiresAt | integer/null | 过期时刻，Unix **毫秒**，不是 JWT exp 的秒 |

无效 Token 返回 `valid=false`，其他字段为 null；空请求字段仍是 400。
当前实现捕获认证异常（含会话不可用）也返回无效结果，不应将本接口故障全部描述为 HTTP 503。
验证只检查 JWT 和当前 jti 的撤销，不实时查账户禁用/角色，也不保证会话仍存在。

**暴露差异**：认证 Controller 没有额外的服务间调用身份校验；网关 `/api/auth/**` 路由覆盖
此路径，但不在匿名白名单。因此经网关访问需先验证 Authorization，再验证请求体 token。
“供内部调用”不等于网络级隔离已完成，后续收紧要求必须走兼容和安全审查。

## `GET /api/auth/me`（现有实现补录）

要求 `Authorization: Bearer <access-token>`。验证 Token 与撤销状态后读取账户实时状态。
成功返回字符串字段 `accountId`、`loginName`、`role`、`type`、`sessionId`，不返回密码哈希或资料表字段。
无效/撤销 Token 或账户不存在为 401，禁用为 403；不建立新会话。
经网关缺少 Header 为 401；若在受控内部直连缺少 Header，当前 Controller 参数绑定返回 400，
这是当前边界差异，不代表客户端可以绕过网关。

## JWT Claims

Access Token 使用 `auth-service` 注入的 HMAC-SHA256 密钥签名，包含：

| Claim | 语义 |
| --- | --- |
| `sub` | `auth_account.id` |
| `typ` | 主体类型，`USER` 映射为 `user`，`ADMIN` 映射为 `admin` |
| `roles` | 角色数组，首个值与账户角色一致 |
| `jti` | 当前 Access Token 唯一 ID |
| `sid` | 刷新会话 ID |
| `iss` / `aud` | 默认 auth-service / media-platform；分别由 AUTH_JWT_ISSUER / AUTH_JWT_AUDIENCE 注入 |
| `iat` / `exp` | 签发时间和过期时间，Unix 秒 |

JWT 不保存密码、Refresh Token、完整个人资料或其他敏感信息。Redis 保存 `sid` 会话、Refresh Token 哈希索引和已注销 `jti`；数据库仍是账户角色和状态的唯一事实来源。

## 错误语义

| HTTP | 含义 |
| ---: | --- |
| `400` | 请求字段缺失或格式错误 |
| `401` | 凭据错误、Token 无效、过期或已失效 |
| `403` | 账户已禁用 |
| `409` | 登录名已存在（注册预检查） |
| `500` | 未预期服务错误，包括尚未专门映射的异常 |
| `503` | Redis 会话依赖不可用（verify 的捕获行为见上文） |

错误响应的 `data` 固定为 `null`。服务端不得在日志中输出密码、完整 Token 或 Refresh Token。

## 兼容与下线

该版本新增 `/api/auth/*` 路径，不承诺兼容旧单体 `/calles/login`、`adminToken` 或旧 JWT。旧账号首次登录升级 BCrypt、旧接口适配和网关切流需单独设计；在兼容期结束前不得删除旧实现。

## 验证与实现差异的维护

已知调用方为新客户端及网关；暂无已登记的旧客户端兼容消费者。登录和刷新创建/轮换会话，
不能无条件重试；只读验证不产生账户写入，网关侧可能产生缓存写入。
认证直连响应与网关拦截响应分开核验，HTTP 状态与 `code` 均须检查；当前响应没有额外的字符串 errorCode 字段。

本次补录不删除旧字段、不更改版本或旧接口语义。尚未明确的禁用即时性、注销缓存窗口、
并发冲突和内部隔离见 [认证设计](../../reference/authentication.md)，
测试矩阵见 [测试指南](../../guides/testing.md)。后续修复若收紧鉴权或改变错误语义，仍须按契约规则演进。
