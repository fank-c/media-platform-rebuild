# 认证与网关设计

## 1. 范围和状态

本页承接认证阶段补全方案、网关认证方案、执行报告和注释报告的设计内容。
字段和外部行为统一维护在 [认证 HTTP v1](../contracts/http/auth-api-v1.md)，
验证步骤统一维护在 [测试指南](../guides/testing.md)。本页不是新的实施授权或独立验收结论。

2026-09-07 第一阶段收尾静态核对：注册、登录、刷新、注销、验证、当前账户查询及网关拦截已有代码；
旧账号迁移、旧 Header 适配、生产流量切换和完整安全验收未完成。

## 2. 职责与实现入口

| 组件 | 职责 | 源码 |
| --- | --- | --- |
| AuthController | 协议校验和响应封装 | [HTTP 层](../../service/auth-service/src/main/java/com/calles/platform/auth/interfaces/http/AuthController.java) |
| AuthService | 注册、账户校验、令牌与会话编排 | [应用层](../../service/auth-service/src/main/java/com/calles/platform/auth/application/AuthService.java) |
| TokenService / SessionService | JWT 与随机凭据；Redis 会话和撤销 | [认证安全技术目录](../../service/auth-service/src/main/java/com/calles/platform/auth/infrastructure/security) |
| AuthGlobalFilter | 匿名路径、鉴权、身份头清理及注入 | [网关过滤器](../../service/gateway-service/src/main/java/com/calles/platform/gateway/filter/AuthGlobalFilter.java) |
| AuthServiceClient / AuthCacheService | 验证回源、缓存与失效 | [网关服务目录](../../service/gateway-service/src/main/java/com/calles/platform/gateway/service) |
| common-web | Servlet 请求身份上下文解析与清理，不验签 | [公共 Web 模块](../../common/common-web) |

`auth_account` 只归认证服务；注册不负责创建 `user_profile`，不在本地事务中跨服务写资料表。

## 3. 认证流程

1. 注册校验登录名和密码后创建 `USER/ACTIVE` 账户，使用 BCrypt 保存哈希；返回账户摘要，不自动登录。
2. 登录读取认证账户并校验密码和状态，签发短期 Access Token 和随机 Refresh Token，建立 Redis 会话。
3. 刷新先通过 Redis Lua 消费旧刷新索引并给原 `sid` 标记 `inflight`，再读取账户状态和角色；
   只有同一 `rotationId` 仍持有该会话时，才会写入新索引、更新会话并返回新令牌对。注销若发生在
   begin 与 finish 之间，会删除该会话，finish 只能失败，不能复活 `sid`。旧刷新凭据一旦 begin 成功，
   即使账户校验、签发或 finish 失败也不会恢复；失败清理仅按同一 `rotationId` 删除在途状态。
4. 注销删除当前 `sid` 刷新会话，将当前 Access Token 的 `jti` 撤销到自然过期。
5. `/verify` 校验 JWT 和当前 `jti` 的撤销状态，不读取最新账户状态；`/me` 额外读取账户并检查禁用状态。

刷新会话 Hash 包含 `subjectId`、`role`、`refreshHash`、ISO 格式 `expiresAt`，以及轮换期间短暂存在的
`rotationId`/`rotationState=inflight`。旧会话缺少两个轮换字段时视为 ready；字段只出现一个、状态未知或
Redis 类型异常会失败关闭，不尝试猜测恢复。刷新会话和撤销列表并不等同：删除刷新会话不能据此推断该会话
历史签发的每个 Access Token 都即时失效。

刷新路径记录 `auth_refresh_total{outcome=success|invalid|disabled|unavailable|failed}` 与
`auth_refresh_duration`；条件 abort 未明确删除当前 in-flight 状态时，额外记录
`auth_refresh_abort_total{outcome=failed}`。所有标签均为固定结果类别，绝不写入 sid、rotationId、账户 ID 或令牌。

## 4. 网关流程和同步依赖

```text
客户端 → 网关清理/校验 → 缓存命中或 auth-service /verify → 业务服务
                               ↓
                     Redis：Token 摘要键和验证结果
```

- 匿名白名单以 [网关 YAML](../../service/gateway-service/src/main/resources/application.yml) 为准：
  `ping/login/register/refresh` 和管理探针；匿名请求同样移除客户端伪造身份头。
- 非白名单必须带 Bearer Token。网关使用 `gateway:auth:<sha256>` 缓存验证结果，不存 Token 原文。
- 缓存 TTL 默认 `10m`；有 `expiresAt` 时取配置 TTL 与剩余寿命较小值。当前实现也缓存无效结果。
- 未命中时同步调用 `lb://auth-service/api/auth/verify`，超时 3 秒，无显式重试。
  Redis 缓存故障回源；认证调用故障转换为无效结果，对客户端返回 401，而不是放行。
- 验证成功后写入 `X-User-Id`、`X-User-Role`、`X-User-Type`、`X-Session-Id`。
- 经网关完成注销请求后尝试删除当前 Token 缓存；删除异常被记录并吞掉，不保证所有故障场景即时撤销。

该同步验证是请求放行前必须即时取得身份的实现。现有 ADR 0001 记录的是总体基线，
该具体同步调用和缓存策略的独立 ADR 仍需后续审查补齐，本次不补造“已接受”决策。

## 5. 安全边界与待验收项

| 差异或风险 | 影响与验收要求 |
| --- | --- |
| common-web 只解析 Header，不验签 | 业务服务必须隔离在可信网络；不能将它描述为完整防御性鉴权已实现 |
| `/verify` 不读取账户最新状态 | 禁用或角色变更不保证所有受保护接口即时生效；需明确策略并测试 |
| 缓存与撤销不是原子操作 | 直接调用认证服务注销、缓存删除失败或并发回填可能保留旧缓存；需故障和竞态测试 |
| 注销只撤销当前 `jti` | 同会话刷新前后的旧访问令牌需要单独验证，不承诺全会话即时撤销 |
| 刷新 begin/finish 是 Redis 内原子步骤，不是跨 Redis/数据库事务 | 账户校验后的并发禁用、finish 成功后响应丢失及 Redis 脚本运行错误仍须按故障矩阵验收；不会自动恢复旧 Refresh Token |
| `/verify` 名义上为内部接口 | 网关 `/api/auth/**` 路由仍涵盖此路径，且不在匿名白名单；不能宣称网络级内部隔离已完成 |
| 登录名大小写说明与库排序规则 | SQL 使用 `utf8mb4_unicode_ci`，不能按旧注释承诺大小写敏感；需契约与实际库共同核验 |
| 注册并发唯一性 | 预查询不足以保证并发友好错误，需验证唯一键冲突映射 |
| 可观测性与限流 | 未据源码盘点确认完整业务指标、追踪传播或限流闭环；生产前需补齐 |

本次仅记录差异，不修改鉴权语义、缓存策略、数据库或接口兼容性。

## 6. 历史证据和验证状态

2026-09-03 的原认证阶段执行报告记载 common 与 auth-service 打包成功，同时明确使用过跳过测试编译的方式，
测试适配及手动链路未完成。同日的原注释报告记载补充中文注释并声称可编译，但缺少可复现的独立审查证据。
这些是历史自报结果，不据此标记当前构建、测试、注释准确性或独立验收通过。

当前工作区有 `AuthServiceTest`、`PasswordServiceTest`、`TokenServiceTest`；认证的事件工厂和 Outbox
分别位于 `infrastructure/messaging`、`infrastructure/outbox`，用户端以本地 Payload 的类型化信封进入
资料初始化事务。该目录与对象绑定调整不改变 JWT、HTTP 或账户状态语义。
未发现网关测试目录。实际执行状态以本轮测试命令及结果为准，不以文件存在判断用例通过。

旧计划中的整段 Java/YAML 副本不再维护；实现定位使用本页源码链接，契约和配置各自保留唯一来源。

## 7. 回退与交接边界

未完成旧账号兼容与切流演练前，新认证不能替换旧系统入口。实现回退应使用已验证版本，
保留认证表及必要会话数据，不能以删除账户表、清空 Redis 或轮换密钥代替回退。
切流、会话失效策略和密钥变更均需另行明确授权。阶段门槛见 [迁移计划](../migration-plan.md)。

## 8. 注册事件的快速投递与恢复

注册仍在一个本地事务内写入 `auth_account` 与 `auth_outbox`，因此 API 返回成功只表示这两项已提交；
不会等待 RabbitMQ 确认，也不表示 `user_profile` 已生成。事务提交成功后，
`AuthOutboxDispatchNotifier` 只把 `eventId` 交给有界快速执行器。队列拒绝、快速开关关闭或进程停机只会
丢失这次内存提示，持久化记录仍由独立扫描恢复。

快速任务和扫描任务都在真正开始处理时通过相同的条件领取语句竞争记录；只有领取成功才增加 `attempts`
并创建租约。Confirm ack 且无 return 后才按 `claim_token` 条件标记 `PUBLISHED`；旧租约持有者不能覆盖
新持有者的状态。该机制仍是至少一次投递，user-service 的事件幂等是必需条件而非优化。

默认 `AUTH_OUTBOX_FAST_DISPATCH_ENABLED=false`、扫描间隔为 1 秒。快速通道开关与线程数仅在启动期读取，
修改后须受控重启；启用、扫描降频及性能阈值需要隔离 MySQL/RabbitMQ 的测量和独立验收。完整取舍、参数、
验证矩阵和回退见[快速投递修改方案](auth-outbox-fast-dispatch-plan.md)及
[ADR 0005](../adr/0005-auth-outbox-post-commit-fast-dispatch.md)。
