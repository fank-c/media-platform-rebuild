# Refresh 会话原子性补强方案

- 状态：**代码已实现；2026-09-07 随 auth/user 第一阶段收尾核对，仍需独立运行验收证据**。
- 原草案用于实施前讨论；下文设计和测试矩阵不是逐项通过记录。当前实现入口为 `AuthService.refresh`、`SessionService` 和 `src/main/resources/redis/auth-session/`，已存在 HTTP、并发与 Redis 集成测试源码。
- Redis 会话变更的决策补录见 [ADR 0006](../adr/0006-auth-refresh-session-atomicity.md)（提议，待确认）。
- 日期：2026-09-07；仅 auth-service 的刷新会话一致性，不涉及切流。
- 当前代码包含两阶段 Redis Lua 轮换、应用编排、低基数指标和自动化测试源码；本次阶段收尾只整理文档，不修改运行配置、部署状态或相邻 `../calles`。

## 1. 实施前的问题与目标（历史基线）

`AuthController.refresh` 只接收请求体中的 `refreshToken`，没有 Authorization 要求。
`AuthService.refresh` 先消费旧凭据，再读 `auth_account` 校验账户，最后调用 `issueTokens`。
`SessionService.CONSUME_SCRIPT` 删除旧索引及 session，未比较提交哈希与 session.refreshHash，
也未显式检查会话剩余寿命；`issueTokens` 通过 `createSession` 无条件重建同一个 sid。
因此会出现：refresh 消费并删除 session → logout 删除空 session → refresh 重建 session。
实施前的 Mockito 测试只验证部分调用顺序；当时未发现真实 Redis 脚本及该并发交错证明，也未发现控制器测试。当前已新增测试源码，执行证据仍以测试指南为准。

目标是让“已经被 logout 删除的 session”不能被在途 refresh 恢复，同时补强索引与会话绑定检查。
必须保留：旧 refresh 一旦成功消费，即使随后账户不存在、禁用或签发失败，也不能再次使用。
推荐两阶段 Lua：begin 消费旧索引、保留带在途标记的 session；finish 仅更新仍存在的同一会话。
不采用“先读数据库、最后一次性换令牌”：它会使账户校验失败时旧凭据仍可用，改变现有契约。

## 2. 范围与明确非目标

- 保持 `/api/auth/refresh` 请求、成功响应、JWT Claims、sid 复用及滑动刷新有效期策略。
- 不新增 Authorization，不撤销旧 access，不改 `/verify`、`/me`、gateway 或网关缓存。
- logout 仍删除 sid 会话并只撤销请求携带 access 的 jti；不新增全会话 access 撤销机制。
- 若 finish 先成功，logout 后删除 session，新 access 的 jti 未必被撤销，仍可能通过 verify。
  这不属于本轮修复，不能承诺“logout 返回后所有 access 均无效”或完整注销安全。
- 数据库校验完成后账户并发禁用不在本轮保证内；不引入数据库与 Redis 分布式事务。
- 不新增锁、框架、基础设施、生产重放或数据库迁移；不修改相邻 `../calles`。

## 3. Redis 数据定义与兼容

键名保持 `auth:session:<sid>`、`auth:refresh:<sha256>`、`auth:revoked:jti:<jti>`。
所有者仍为 auth-service；沿用当前单机 Redis 模型，动态拼接关联键不声称兼容 Redis Cluster。
refresh 原值和 access 原值不得入 Redis 会话、日志或指标标签；refresh 索引值仍为 sid 字符串。

| session Hash 字段 | 默认、约束与生命周期 |
| --- | --- |
| `subjectId` | 原字段；非空账户 ID，轮换不能改变所属账户 |
| `role` | 原字段；合法角色；finish 使用本次数据库读取的角色更新 |
| `refreshHash` | 原字段；SHA-256；inflight 时保留旧哈希，finish 改为新哈希 |
| `expiresAt` | 原字段；ISO Instant 字符串，保留格式；finish 写入新到期时刻 |
| `rotationId` | 新可选字段；缺失表示无在途轮换；begin 写入每次请求唯一的随机 nonce |
| `rotationState` | 新可选字段；缺失表示 ready，唯一显式取值为 `inflight`；与 rotationId 同生共灭 |

`rotationId` 仅作内部请求归属比较，不是 distributed lock；没有解锁、续租或超时抢锁逻辑。
旧会话两字段都缺失视为 ready；仅一个存在、空 nonce 或未知 state 均拒绝刷新，不猜测恢复。
begin 不续 session TTL；finish 成功删除两个新增字段；logout/失败清理删除整条 session。
进程崩溃留下 inflight 时，不恢复旧索引、不自动接管；状态随原 TTL 消失，客户端需重新登录。
新登录总是新 sid，不复用失败刷新 sid；旧会话无需批量回填或 reset。

## 4. 两阶段脚本与应用编排

### 4.1 时间与脚本公共约束

`expiresAt` 当前是 `Instant.toString()`，禁止 Lua `tonumber(expiresAt)` 或字符串大小比较判过期。
begin 对当前 session 和旧索引均要求 `PTTL > 0`；`-1` 永久键、`-2` 缺失及 `0` 均不能放行。
finish 对当前 session 同样要求 `PTTL > 0`；begin 后旧索引已消费，不再要求它存在。
Java 在 begin 成功后解析 ISO 字符串验证格式；解析失败返回 503 并按 rotationId 清理，不返还旧凭据。
新预算在生成候选 token 时取一次 `t0`，`deadline = t0 + refreshTokenTtl`，ISO 值由 deadline 生成。
finish 调用前从同一 deadline 计算正整数毫秒剩余量；预算耗尽则拒绝，不重新赋予完整 TTL。
推荐 finish 使用同一 epoch 毫秒 deadline 设置两个键的 `PEXPIREAT`，以 Redis TIME 校验尚未到期；
ISO 值和 epoch 值均由同一个 Java Instant 生成，不另引入持久化时间字段，避免调用延迟延长寿命。
要求应用与 Redis 时钟同步；测试验证 ISO 与 Redis 到期时刻在毫秒精度容差内一致。

Lua 原子性是执行隔离，不是运行错误时自动回滚：前面已写入的数据可能保留。
所有脚本先检查 TYPE、参数数量/格式、合法状态、TTL/数值范围和关联关系，再进入写入段。
应在首次写入前构造返回值并完成可失败的编码，避免写后 JSON 编码失败造成不确定结果。
TYPE 检查区分不存在与错误类型；错误类型/结构损坏返回受控依赖异常，不暴露 Redis 错误文本。
参数预检不能消除 OOM、连接中断等风险；脚本异常按 503 拒绝签发，不能宣称全有或全无。

### 4.2 begin：消费资格而不是删除会话

1. Java 生成唯一 rotationId，计算提交 refresh 的 SHA-256，调用 begin，不读取或相信客户端 sid。
2. 预检旧索引为 string 且 PTTL>0；由索引取 sid，再预检 session 为 hash 且 PTTL>0。
3. 校验必需字段存在、角色合法、`session.refreshHash == 提交哈希`，索引值确为该 sid。
4. 只允许 ready；同一个旧凭据重复请求或已有 inflight 返回 INVALID，不访问数据库。
5. 在写入前准备包含 sid、subjectId、role、旧哈希、ISO expiresAt、rotationId 的返回快照。
6. 先写入 rotationId/inflight，再删除旧索引；保持 session 原有 TTL，返回 BEGIN_OK。
   即使删除索引前发生运行错误，inflight 也会拒绝再次 begin；不得自动把状态恢复 ready。

索引缺失、会话缺失、无正 TTL、哈希不匹配或轮换竞争返回 401；不删除不属于本次请求的会话。
同一旧 refresh 并发时最多一个 begin 成功；无其他故障且账户正常时最终一胜一负。

### 4.3 账户校验、生成候选 token、finish

1. begin 成功后再 `selectById(subjectId)`；不存在/非法角色或状态返回 401，禁用返回 403。
2. 数据库校验通过后生成新 refresh 和 access，但仅保存在请求内存，不组装成功 HTTP 响应。
3. 调用 finish，传入 sid、subjectId、rotationId、旧/新哈希、最新角色、ISO deadline 和 epoch deadline。
4. 写前确认 session 存在、TYPE 正确、PTTL>0，subjectId、旧哈希、rotationId、inflight 全匹配。
5. 预检新索引不存在且新哈希不同于旧哈希；禁止覆盖任何已有索引，碰撞作为受控 503 失败。
6. 校验新时间预算有效；使用单条 `SET ... NX PXAT` 写新索引并设置绝对到期，确认成功后再更新 session 字段和同一绝对到期，
   最后移除 rotationId/state，返回 FINISH_OK；不要在可能失败的步骤之前解除 inflight。
7. 只有明确收到 FINISH_OK 才返回新 token 对；缺失 session 或归属不符返回 401，绝不调用 create。

旧会话在数据库校验期间到期，即使新预算仍有效也不能 finish；新的 deadline 不能复活已过期 sid。
无效参数/脚本错误与 Redis 依赖故障映射 503；合法但已过期的轮换映射 401。
新索引若因部分写入成为悬挂键，begin 仍须通过 session 哈希与 ready 检查，不能单凭索引认证。

### 4.4 失败清理与结果未知

- 增加 abort Lua：先比较 sid、subjectId、rotationId/inflight，再删除本请求会话。
- 删除关联索引前检查类型与索引值确属该 sid；允许清理本次旧哈希和已生成候选新哈希的索引。
- rotationId 不符、已消失或 session 不存在即 no-op；不得用无条件 deleteSession 作刷新失败清理。
- 已知账户失败、签发失败和 finish 失败执行条件清理；清理失败只留安全日志/指标，不恢复旧索引。
- 保留原业务失败 401/403；清理依赖失败不能覆盖已经确定的账户结果；数据库未知异常沿用现有 500。
- begin/finish 客户端 timeout 表示服务端结果未知：返回 503，不自动重试，不返回未确认 token。
- timeout 后可尽力执行同 nonce 的条件 abort；它不能删除已完成轮换或别的请求拥有的状态。
- finish 已成功但响应丢失时，候选新凭据可能存于 Redis 而客户端未收到；接受重新登录代价。
- 不增加“查询结果后补发 token”、自动恢复旧 token、后台接管或跨请求 nonce 复用协议。

### 4.5 logout 交错保证

logout 仍调用删除会话脚本：ready/inflight 都删除，关联 refresh 索引仅在确属该 sid 时删除。
脚本也补齐写前类型校验；删会话与撤销当前 jti 仍是两个步骤，不宣称本轮将其变为一个事务。

| 原子步骤顺序 | 必须观察到的结果 |
| --- | --- |
| logout → begin | begin 401，不读数据库、不生成新凭据 |
| begin → logout → finish | finish 401，session 不复活，不返回候选 token |
| begin → finish → logout | logout 删除更新后的 session/新索引；新 access 是否仍有效见非目标 |
| begin A → begin B | B 401；A 可继续，B 不得清理 A 的状态 |
| abort A → 新登录 B | B 使用新 sid，不受 A 迟到清理影响 |

## 5. 按文件实施步骤（审查通过后执行）

以下 Java 路径均相对 `service/auth-service/src/main/java/com/calles/platform/auth/`。

1. `infrastructure/security/SessionService.java`：拆为 create/begin/finish/abort；替换 consume，
   强化删除脚本；定义内部结果与轮换快照，删除“Lua 失败自动回滚/刷新重建会话”等误导注释。
2. `application/AuthService.java`：登录走 create；刷新走 begin→DB→候选→finish→响应，异常走条件 abort。
   拆开现有 `issueTokens` 的生成与持久化职责，禁止 refresh 间接走 createSession。
3. `infrastructure/security/TokenService.java`：原则上复用现有随机令牌/JWT 能力；nonce 可独立 UUID 生成，
   不改 Claims、验签和旧 access 行为；修改到的类型、字段、私有方法与关键步骤补齐中文注释。
4. `interfaces/http/AuthController.java`、DTO、`exception/AuthException.java`：只核对契约与错误映射，
   无必要不改业务接口；401/403/503 与 ApiResponse.code/data 保持现状，空请求字段仍按 400。
5. 测试目录 `service/auth-service/src/test/java/com/calles/platform/auth/`：更新 `application/AuthServiceTest.java`，
   新增 `infrastructure/security/SessionServiceRedisIntegrationTest.java`、`application/AuthRefreshConcurrencyTest.java`
   和 `interfaces/http/AuthControllerTest.java`；不以 Mockito 替代脚本验证。
6. 实施后同步 `docs/reference/authentication.md`、`docs/contracts/http/auth-api-v1.md`、
   `docs/guides/testing.md` 的行为和证据边界；Redis schema 说明同步数据库参考文档。
7. 决策已补录为 [ADR 0006](../adr/0006-auth-refresh-session-atomicity.md)（提议），记录 nonce 状态、失败关闭、部署隔离和回退限制；维护者确认前不改为已接受。

## 6. 验证与验收门槛

- 单元测试：begin 失败不查 DB；禁用/不存在仍消费旧凭据；候选签发失败触发条件清理；
  finish 失败不返回 token、不 create；旧清理不能误删新状态；登录、角色刷新与 JWT 测试回归。
- HTTP 测试：refresh 无 Authorization 成功；重放 401、禁用 403、Redis 503、空字段 400；
  校验 HTTP 和 ApiResponse.code/data、既有 TokenResponse 字段；verify/gateway 不改行为。
- 真实 Redis：正常轮换仅新索引可用；缺失索引/session、错哈希、错类型、非法参数、永久 TTL、
  到期边界、旧 schema、新索引碰撞、非法 state、nonce 不匹配逐项断言键值及剩余 TTL。
- 错误注入：写前参数错误不得改键；隔离 Redis 执行“先写后报错”探针证明无自动回滚，
  并验证应用脚本出错后拒绝旧凭据/候选凭据，悬挂键到期，不使用生产数据。
- 并发：两个独立 SessionService/Redis 连接模拟不同实例；用 CountDownLatch 在 DB 返回或 finish 前
  精确阻塞，不靠 sleep 猜时序；覆盖上表及 begin 后 TTL 到期、崩溃遗留 inflight、迟到 abort。
- 结果未知：测试包装器在真实脚本执行后丢弃回复并抛 timeout；另测发送前失败；
  断言无自动重试、无成功响应，最终键状态可能不同但旧凭据绝不被主动恢复。
- 隔离 Redis 地址通过测试环境传入，不新增生产配置或基础设施依赖；依赖不可用记为未验证而非通过。
- 实施验收在无冒号的隔离副本以 JDK 17 编译及运行 auth 模块测试，报告精确命令和实际 JDK；
  另启动 auth 做 HTTP 探针；本次阶段收尾未复跑构建或 Redis，不声称运行验证通过。
- 记录 refresh 成功、竞争拒绝、依赖失败、abort 失败计数与耗时；使用现有指标设施，
  标签仅固定结果类别，不放 sid/nonce/token；通过现有受控监控入口观察，不新增公开管理端点。

## 7. 启用、回退与交接

1. 审查通过并完成真实 Redis/HTTP 验收后，先确认实例清单、部署窗口和请求排空机制。
2. 新字段对旧数据兼容不等于对旧执行逻辑兼容：旧实例仍可能 consume 删除并 create 重建，
   也不认识 inflight；禁止让新旧实例混合处理 refresh/logout 来完成普通滚动升级。
3. 启用前必须停止所有旧实例处理 refresh/logout 并排空在途请求，再开放新逻辑；
   无法证明排空则保持停用并报告阻塞。生产入口控制或网关流量调整必须另获用户明确授权。
4. 不批量补字段、不恢复已消费索引、不 reset Redis；ready 旧会话按默认值接入，inflight 等 TTL。
5. 上线观察 401/403/503、延迟、abort 失败及并发回归证据，单纯服务启动不算修复验收。
6. 回退先停新逻辑并排空；优先前向修复。直接启用旧 refresh 实现会重新引入竞争缺陷，
   不作为安全回退：必要时保持刷新停用，让用户重新登录，直至安全版本可用。
7. 若必须回退旧二进制，应另行批准风险且保持 refresh 停用；不得导入旧 Redis 快照复活凭据。
   不通过清空会话规避兼容；已有不可用状态自然到期，保留数据并记录影响。
8. 交接记录实际测试命令、结果、未验证项、启用窗口、排空证据与剩余 access/禁用并发风险；
   未经独立验收不标记完成，不将待审 ADR 改为 accepted，不擅自执行上述部署动作。

## 8. 审查依据与本轮验证边界

源码依据：[AuthService](../../service/auth-service/src/main/java/com/calles/platform/auth/application/AuthService.java)、
[SessionService](../../service/auth-service/src/main/java/com/calles/platform/auth/infrastructure/security/SessionService.java)、
[认证 HTTP v1](../contracts/http/auth-api-v1.md)。Redis 官方脚本原子执行和错误处理资料供实施审查复核：

```text
https://redis.io/docs/latest/develop/programmability/eval-intro/
https://redis.io/docs/latest/develop/programmability/lua-api/
```

本轮完成源码对照、文档本地链接与空白检查；未执行 Java 构建、真实 Redis 并发测试、HTTP 探针或部署。
本方案由独立子代理起草，主会话做一致性核对；该核对不替代下一阶段未参与编写者的正式方案审查。
