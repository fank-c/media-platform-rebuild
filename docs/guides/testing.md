# 测试与验证指南

本文整合认证手测、网关手测及通用测试指南。**用例清单不是通过记录**；各节带日期的结果属于对应执行记录，
2026-09-07 第一阶段文档收尾未复跑 Java 测试、启动应用或操作数据库，不将历史结果移作本轮通过证据。历史自报构建结果见
[认证设计](../reference/authentication.md)。

## 1. 当前自动化测试与命令

工作区认证服务有 `AuthServiceTest`、`PasswordServiceTest`、`TokenServiceTest`，
以及 Outbox 发布、状态、Mapper 仓储适配和资料补齐服务测试，覆盖部分账户认证、密码、JWT 与
认证服务内的事件持久化编排，以及刷新 begin/finish 编排、刷新/注销受控交错与 HTTP 错误映射。
`SessionServiceRedisIntegrationTest`
在显式设置隔离 `AUTH_TEST_REDIS_HOST` 时才执行真实 Lua 断言；`AuthRefreshConcurrencyTest` 使用
`CountDownLatch` 固定 begin 后、finish 前的 logout 交错。未提供该变量时 Redis 集成测试会跳过，不能据此推断
Redis 原子流程已经完成验收。当前未发现网关测试源码，也未在 POM 中发现 Spotless、JaCoCo、Testcontainers、JMH 或自动数据库迁移配置。
这些工具不作为已有能力提供命令。

从仓库根目录运行：

```bash
# 全模块构建并运行已有测试；脚本规避当前路径含冒号的问题。
./build.sh
# 公共模块已按当前版本安装后，单独运行认证测试。
./mvnw -f service/auth-service/pom.xml test
# 定位 JWT 行为时，只运行已有测试类。
./mvnw -f service/auth-service/pom.xml -Dtest=TokenServiceTest test
# 仅对自己拥有的隔离 Redis 执行刷新 Lua 集成测试；不要省略地址确认。
AUTH_TEST_REDIS_HOST=127.0.0.1 AUTH_TEST_REDIS_PORT=6379 AUTH_TEST_REDIS_DATABASE=15 \
  ./mvnw -f service/auth-service/pom.xml -Dtest=SessionServiceRedisIntegrationTest test
```

`-DskipTests` 不运行测试；`-Dmaven.test.skip=true` 还会跳过测试编译，两者都不能证明测试通过。
构建失败应保留首次错误、模块和命令，不能跳过失败步骤后将整个任务标记完成。

### 1.1 账号创建消息类型化回归范围

`auth.account.created.v1` 的单元测试必须将“消息结构兼容”和“业务处理”分开验证：认证端验证
`EventEnvelope<AccountCreatedPayloadV1>` 序列化后的字段、UTC 时间文本和敏感信息最小披露；用户端
`AccountCreatedEventDecoder` 验证必需文本字段、未知字段、`aggregateId` 一致性、traceId 安全回退及
v1 的 `version` 宽松转换边界；应用处理器只验证类型输入下的幂等登记和资料初始化事务编排。

RabbitMQ 适配器测试还应覆盖：非法协议转换为不重新入队的拒绝、临时运行时异常继续交给监听容器有限
重试、成功指标仅在处理器成功返回后记录，以及三条路径均恢复进入监听器前的 MDC `traceId`。
这些单元测试不证明真实 Broker 的确认、重试、死信投递或 MySQL 事务回滚，仍须在隔离环境执行集成验证。

### 1.2 Auth Outbox Mapper 访问层验证记录（2026-09-05）

本次将 `auth_outbox` 和资料补齐的 SQL 收敛到专用 MyBatis Mapper，保留 Outbox 仓储的租约、状态和
退避编排，以及补齐服务的事务入口。已在无冒号临时副本执行：

```bash
./mvnw -f service/auth-service/pom.xml test
```

结果：认证服务 **25 项单元测试通过**，其中新增覆盖 Mapper 参数传递、领取快照缺失失败、发布条件更新、
失败状态选择、积压时长归一化，以及补齐进度竞争时不写或写入 Outbox 的行为。

尚未完成真实 MySQL 集成验证：当前环境没有监听 3306 的隔离 MySQL，且 Docker 守护进程套接字无访问权限。
因此，注册/补齐同事务回滚、`FOR UPDATE SKIP LOCKED` 并发领取、过期租约重新领取、旧 `claim_token`
回写拦截及 MyBatis 实际 SQL 映射，仍是独立验收前必须在隔离 MySQL 中执行的项目；不得将单元测试通过
表述为数据库并发与事务场景已验收。

### 1.3 Auth/User 目录收敛与事件类型化验证记录（2026-09-06）

本次将认证服务的安全、消息、Outbox 与持久化适配器收敛到 `infrastructure`，将认证 HTTP 异常处理器
与 Outbox 发布器分别收敛到 `interfaces/http`、`infrastructure/outbox`；认证账户模型及状态收敛到
`domain/account`，用户资料模型及状态收敛到 `domain/profile`；同时压平用户服务当前唯一的资料 HTTP 入口和
账号创建消息入口。`auth.account.created.v1` 的入站 JSON 解码结果仍为本地类型化的
`EventEnvelope<AccountCreatedPayloadV1>`；消息 JSON 协议仍由接口层校验，应用层只处理已校验的本地类型。

已在**无冒号临时副本**、**JDK 17** 下执行：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -pl service/auth-service,service/user-service -am clean verify
```

结果：`common-core`、`common-web`、`auth-service` 与 `user-service` 均成功；认证服务 **31** 项、用户服务
**21** 项单元测试通过，共 **52** 项，失败和错误均为 0。另以 `dependency:tree` 确认此验证实际解析到的
Jackson 版本为 **2.17.1**；源码检索未发现旧包路径、`AccountCreatedEventInput` 或
`DecodedAccountCreatedEvent` 的残留引用。

这只证明受影响模块在该临时副本中可以编译并通过现有单元测试，**不证明**真实数据库 SQL、RabbitMQ
confirm/重试/死信与租约竞争、注册事务回滚、Nacos 配置加载，或经网关的 HTTP 认证链路已经验收。上述
运行时场景仍应仅在资源归属明确的隔离环境中，由独立验收执行。

## 2. 手动验证前置条件

按 [快速开始](quick-start.md) 启动隔离本地环境、初始化表并运行认证和网关。
所有客户端请求通过 `http://localhost:8000`；只有定位内部 `/verify` 行为时使用受控服务直连。

测试账户、密码和 Token 仅保存在本地受控客户端中，不进入仓库、截图、日志或验收正文。
使用 HTTP 客户端私有变量保存敏感响应，报告只记状态码、非敏感字段和是否满足断言。

```bash
# 无敏感凭据的最小连通性探针。
curl -i http://localhost:8000/api/auth/ping
# 受保护且确实已实现的接口；缺少凭据应被网关拒绝。
curl -i http://localhost:8000/api/auth/me
```

不用尚未实现的 `/api/users/profile` 证明业务访问成功；通过认证后得到 404 只说明路由后未找到处理器。

## 3. 认证用例矩阵

请求和响应字段以 [HTTP v1 契约](../contracts/http/auth-api-v1.md) 为准。

| 场景 | 操作 | 核验内容 |
| --- | --- | --- |
| 匿名探针 | 不带 Token 请求 ping | HTTP 200，统一响应 |
| 注册 | 合法登录名与测试密码 | 账户 ID、USER/ACTIVE，不返回 Token；只写认证表 |
| 非法注册 | 空字段、非法名称、过短密码、超过 72 UTF-8 字节 | 400，不写入账户 |
| 重复注册 | 顺序和并发提交同名账户 | 顺序冲突 409；并发唯一键错误映射作为待验收项 |
| 大小写 | 使用仅大小写不同的名称 | 按实际数据库排序规则验证，不假定大小写敏感 |
| 登录 | 正确凭据 | Access/Refresh Token、expiresIn 和角色符合契约 |
| 错误凭据 | 不存在账号、错误密码 | 同为 401，不泄漏账号存在性 |
| 禁用账户 | 由获授权的隔离测试夹具准备 | 登录/刷新/me 拒绝；verify 和缓存行为另行验证，不直接修改共享账户 |
| 当前账户 | 有效 Token 请求 me | 返回实时角色和状态校验后的账户摘要 |
| Token 验证 | 内部 verify 分别使用有效、过期、伪造、已撤销 Token | 有效返回身份和毫秒 expiresAt，无效返回 valid=false |
| 刷新轮换 | 使用同一刷新凭据两次及并发调用 | 旧凭据仅一次 begin；完成后只有新索引可用；失败后不复用 |
| 刷新/注销交错 | begin 后阻塞，在 finish 前执行 logout | finish 返回 401，不复活 sid 或候选新索引；不能只用 sleep 推测时序 |
| Redis 结构与结果未知 | 错类型、无 TTL、nonce/state 不匹配、finish timeout | 503 或 401 按契约失败关闭；无自动重试、无旧凭据恢复、悬挂状态仅自然到期 |
| 注销 | 注销后访问 me，并重用刷新凭据 | 当前 jti 被拒绝，刷新会话不可用 |
| 重复注销 | 分别直连受控认证服务和经网关重复调用 | 无额外状态副作用；记录网关提前拒绝导致的响应差异 |
| 同会话旧令牌 | 刷新后注销新令牌，再使用旧访问令牌 | 记录撤销范围，不把当前 jti 撤销等同于全会话访问令牌撤销 |

## 4. 网关、安全与故障矩阵

| 场景 | 验证要求 |
| --- | --- |
| 匿名白名单 | ping/login/register/refresh 按契约放行；伪造身份头被移除 |
| 无凭据、格式错误、无效凭据 | 401 与统一错误结构，不泄漏 Token |
| 有效凭据 | 用 me 验证真实链路；身份头下传需受控测试接收端，不能靠响应推断 |
| 客户端伪造身份头 | 覆盖四个 X-User/X-Session Header；下游只得到网关计算值 |
| 缓存命中与到期 | Key 使用摘要；TTL 不超过配置值和有效 Token 剩余寿命；无效结果当前也可能缓存 |
| 经网关注销 | 正常场景缓存被清理；注入删除失败与并发回填验证残留窗口 |
| 直连注销 | 验证已有网关缓存可能仍存活，不预先宣称即时撤销 |
| Redis 故障 | 网关缓存层回源；认证会话层按失败关闭，区分实际 HTTP 语义 |
| 认证调用故障 | 验证 3 秒超时、无显式重试，网关最终拒绝而非放行 |
| 业务服务直连 | 检验网络隔离；common-web 不验签，不能仅用 Header 当可信身份 |
| 上下文清理 | 请求结束后 ThreadLocal 无身份残留；异步任务不错误继承身份 |

故障注入只在自己拥有的隔离环境执行。不得停止共享 Redis/Nacos，也不得暴露生产诊断接口。
缓存核验优先使用受控客户端 `SCAN` 和单键 TTL，不使用阻塞式全库 `KEYS`，不打印认证会话内容。

## 5. 如何记录结论

每条结果记录：代码版本或工作区状态、隔离环境、命令/步骤、预期、实际、脱敏证据、是否通过及未覆盖项。
构建、单元测试、服务启动、集成链路和独立验收分开陈述。

接口契约变更必须补兼容场景；数据库与消息验证还需覆盖幂等、迁移和失败恢复。
发现问题先给证据回流实施，不在只读验收中顺手修复。没有独立审查时明确注明剩余风险。

### 1.4 Auth Outbox 提交后快速投递验证记录（2026-09-06）

本次实现将注册事务提交后的快速提示、按 ID 条件领取、独立扫描恢复和低频积压统计拆开：快速任务只携带
`eventId`，开始执行时才领取；扫描不再批量提前设置租约；快速与扫描复用 Confirm/return 判定及
`claim_token` 条件回写。默认快速开关仍为 `false`，未启动服务、未连接数据库或 RabbitMQ。

已在无冒号、无符号链接临时副本 `/tmp/media-platform-auth-outbox-fast-dispatch.PK8BSM`、JDK 17 下执行：

```bash
./mvnw -pl service/auth-service -am clean test
```

结果：`common-core`、`common-web`、`auth-service` 成功；auth-service **37** 项单元测试通过，0 failure、
0 error。新增/调整用例覆盖提交后才提交任务、无事务安全降级、条件领取竞争失败、扫描逐条复用、次数耗尽
收敛、Confirm 成功/失败和旧 token 条件回写；这只是 Mockito/线程本地事务同步层面的单元证据。

尚未验证真实 Spring 事务代理加隔离 MySQL 的提交可见性、MyBatis SQL 映射与多连接竞争、真实 RabbitMQ 的
Confirm/return/nack/超时、停机中断、Nacos 配置加载、服务启动探针、user 消费端回归或性能收益。快速开关
保持关闭，任何隔离环境启用和扫描降频均须按[快速投递修改方案](../reference/auth-outbox-fast-dispatch-plan.md)
执行并由独立验收确认。

## 第一阶段提交前证据边界（2026-09-07）

auth/user 第一阶段功能由用户确认收尾。本轮核对了文档导航、当前实现与测试源码，未复跑构建或运行验收。
刷新已有 begin/finish/abort Lua、应用编排、HTTP 测试和 Redis 集成测试源码；集成测试受环境开关控制，
不能因文件存在或普通测试成功而宣称 Redis 用例执行过。Outbox/MySQL/RabbitMQ、可信网关边界及刷新
竞争场景的实际运行报告仍需单独核验；旧 Access Token 撤销和网关缓存窗口仍是已知非目标。

提交前若需要重新验证 Java 变更，优先将当前工作区（含未跟踪源码，排除 `.git`、`.env` 和 `target`）
复制到无冒号的隔离目录，在 JDK 17 下运行 `./mvnw -pl service/auth-service,service/user-service -am verify`。
根路径的 `./build.sh` 可规避部分构建路径问题，但本项目测试曾需无冒号副本，失败时不得靠跳过测试宣称通过。
真实 Redis 测试只指向明确属于自己的隔离资源，记录运行数量、跳过数量和依赖版本，不打印凭据。
