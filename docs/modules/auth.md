# 认证模块 · auth-service

认证模块管理登录账号、密码和登录凭据。昵称、简介等资料属于用户模块：注册只保存认证账号，并通过账号创建消息通知用户模块建档，不跨服务写资料表。下面的 HTTP 业务入口均经网关访问；消息补齐属于内部任务，不提供管理 HTTP 接口。

## Part 1：账号注册

### 创建普通账号

调用 `POST /api/auth/register`，JSON 提交 `email` 和 `password`，不需要已有登录身份。`email` 为符合标准邮箱格式且不超过 255 字符的邮箱，应用层统一进行 trim 并转小写处理；密码至少 8 个字符，同时不得超过 72 个 UTF-8 字节。中文等多字节字符不能只按字符数判断上限。

处理依次为：校验输入、检查邮箱是否已被注册、计算密码哈希、创建 `USER / ACTIVE` 账号，再写入待发送的账号创建通知。账号与通知都在认证库的同一事务内保存；其中一步失败，不能把另一部分当作注册完成。密码使用 BCrypt 哈希保存，不保存明文。

成功响应的 `data` 包含账号 ID、邮箱、角色和状态，HTTP 为 `200`。这表示账号已建立，不表示已登录，也不表示用户资料已生成。调用方下一步应登录；资料是否就绪通过用户模块查询。

预先查到邮箱已注册时返回 `409`，无效输入返回 `400`。数据库唯一约束兜住并发重名，但当前异常处理没有将并发插入的重复键异常单独映射为 `409`，这类竞争可能走通用 `500`，不能承诺所有重名请求都有相同错误状态。公开注册不能指定管理员角色。当前没有找回密码、修改密码或账号启停接口，不把这些能力视为已实现。

源码入口：[注册与登录接口](../../service/auth-service/src/main/java/com/calles/platform/auth/interfaces/http/AuthController.java)、[认证用例](../../service/auth-service/src/main/java/com/calles/platform/auth/application/AuthService.java)、[账号表](../../service/auth-service/db/schema/auth-account.sql)。

## Part 2：登录与登录状态维护

### 登录并取得两种凭据

调用 `POST /api/auth/login`，JSON 提交 `email`、`password`，可选提交 `deviceId` 或通过 `X-Device-Id` 请求头传入设备标识（拦截层注入 `UserContext`，优先取 Header）。服务规范化邮箱（trim 并转小写），查询账号，检查状态，再比对密码。账号不存在或密码错误返回 `401`；代码对已禁用账号单独返回 `403`，不能将当前行为描述为“所有失败都隐藏账号存在性”。

成功后返回 `accessToken`、`refreshToken`、`expiresIn` 和 `role`。访问令牌是携带签名的 JWT，用于后续请求的 `Authorization: Bearer <accessToken>`；刷新凭据用于访问令牌到期后续期，不用来直接调用业务 API。`expiresIn` 是访问令牌有效秒数，不是刷新凭据的期限。

服务将刷新会话及凭据摘要索引写入 Redis，并基于设备标识维护用户会话集合；同一设备重复登录直接替换旧会话，超过最大会话限制（默认 5）时自动淘汰最久未活跃会话。仓库默认访问令牌 900 秒、刷新凭据 2592000 秒，可通过 `auth.access-token-ttl-seconds`、`auth.refresh-token-ttl-seconds` 与 `auth.max-sessions-per-user` 配置；运行环境值本次未读取。

### 刷新与重复提交

调用 `POST /api/auth/refresh`，JSON 为 `{"refreshToken":"<refreshToken>"}`。它不要求有效的访问令牌，网关将此路径列入匿名白名单，但刷新凭据自身必须有效。

1. Redis 原子消费旧刷新凭据，取得本次轮换资格。此时旧凭据已经不能再用。
2. 服务读取账号最新状态，生成候选的新访问令牌和刷新凭据。
3. 只有原会话仍归本次轮换所有，Redis 才完成替换；明确确认成功后才把新凭据返回客户端。

客户端成功后应替换原有两种凭据，避免多个请求同时拿旧刷新凭据续期。重复提交、已过期或已消费的凭据返回 `401`；账号被禁用返回 `403`；会话存储不能可靠操作时可能返回 `503`。旧凭据已被消费后，即使后续失败也不会恢复，不能假设原样重试一定能成功；无法取得有效新凭据时需要重新登录。

### 退出当前会话

调用 `POST /api/auth/logout`，携带当前访问令牌。服务先验证令牌，再删除其刷新会话，并把本次访问令牌的唯一标识加入撤销记录，保留到令牌自然过期。

刷新与退出交错时，轮换提交必须检查会话仍存在，不能靠一次较晚的刷新重新创建已退出会话。这里的“退出”不是所有设备退出，也不是撤销同一会话历次签发的全部访问令牌：当前验证路径不检查会话是否存在，旧访问令牌能否继续通过还受自身有效期、撤销记录和网关缓存影响。

源码入口：[会话与轮换实现](../../service/auth-service/src/main/java/com/calles/platform/auth/infrastructure/security/SessionService.java)。网关缓存限制见[网关文档](gateway.md)。

## Part 3：身份验证与当前账号

### 为网关验证身份

网关内部调用 `POST /api/auth/verify`，JSON 提交 `token`。认证服务检查 JWT 签名、有效期及本次令牌是否已撤销；成功返回 `valid=true` 和主体 ID、类型、角色、会话 ID、过期时间，认证失败返回 `valid=false`。

该入口不会每次读取账号表，也不确认用户资料是否存在。因此账号被禁用或角色变化后，不能据此承诺已签发令牌立即反映变化。`/verify` 没有列入网关匿名白名单，它是网关回源使用的接口，不是客户端获取登录凭据的入口。

### 查询当前账号

客户端调用 `GET /api/auth/me`，携带访问令牌。服务在验签和撤销检查后读取账号表，检查最新账号状态，再返回账号摘要。账号不存在或令牌无效返回 `401`，账号禁用返回 `403`。它与 `GET /api/users/me` 不同：前者回答“当前登录账号是谁”，后者回答“这个用户的资料是什么”。

## Part 4：账号创建通知

### 从注册成功到用户资料生成

为了在消息系统暂不可用时保留通知，注册事务先把事件写进 `auth_outbox`。这张待发送记录表就是 Outbox；它记录“还欠一次通知”，而不是把注册请求与远端消息发送绑在一个事务中。

默认后台每秒扫描候选事件。调度器领取带期限和领取标识的记录，发布器将消息发往 `media.platform.events`，路由键为 `auth.account.created.v1`。只有消息代理确认接收且没有退回消息，才条件更新为已发布；租约过期或失败后可再次领取，超过默认 20 次尝试则进入耗尽状态，不无限重试。

事件正文通过公共信封携带 `eventId`、`eventType=auth.account.created`、`version=1`、主体和追踪信息；载荷提供 `accountId`、`accountType=user`、`createdAt`，不包含密码、登录名或令牌。用户服务按事件 ID 去重。消息代理接收成功不等于用户资料已落库，消费者仍有自己的处理和失败出口。

`auth.outbox.enabled` 默认开启。可选的 `auth.outbox.fast-dispatch-enabled` 默认关闭：开启后在注册事务提交后提示快速发送，拒绝或遗漏提示仍由后台扫描补发。领取属于调度器职责，发布器处理已领取记录的发送与确认，不把两者混写。

### 为已有账号补发建档通知

当前只有内部定时任务，没有人工触发的 HTTP 接口。`auth.profile-backfill.enabled` 默认关闭，`auth.profile-backfill.dry-run` 默认开启；仅显式开启并关闭预览时才写数据。

任务从新认证库读取尚无补齐进度的正常普通账号，分批在同一事务登记补齐进度和 Outbox 事件。它不查询用户库，因此不是精准检测“缺少哪些资料”；已有资料由用户服务幂等跳过。它也不读取旧单体账号，不是旧账号迁移工具。

源码入口：[Outbox 调度](../../service/auth-service/src/main/java/com/calles/platform/auth/infrastructure/outbox/AuthOutboxDispatcher.java)、[发送确认](../../service/auth-service/src/main/java/com/calles/platform/auth/infrastructure/outbox/AuthOutboxPublisher.java)、[补齐用例](../../service/auth-service/src/main/java/com/calles/platform/auth/application/ProfileBackfillService.java)、[默认配置](../../service/auth-service/src/main/resources/application.yml)。

## 验证方式与当前结果

应验证注册重名及事务回滚、登录失败分支、重复刷新和刷新/退出交错、Redis 失败不返回候选凭据，以及消息确认、退回、重投和资料最终生成。

现有[认证测试目录](../../service/auth-service/src/test/java/com/calles/platform/auth)包含应用用例、HTTP、令牌、Outbox 与补齐测试；部分并发测试使用会话替身，不能替代 Redis 原子脚本验证。真实 Redis 测试另由 `AUTH_TEST_REDIS_HOST` 显式启用，应仅指向隔离实例。

本次（2026-09-09）实际完成源码、配置、表结构和相关测试断言的静态核对，以及文档链接与格式检查。未执行 Maven 编译或测试，未启动服务，未执行 MySQL、Redis、RabbitMQ 联调；不沿用旧阶段记录宣称当前工作区验收通过。
