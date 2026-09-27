# ADR 0006: 认证模块自属 Outbox 与账号创建领域事件

## 状态

**Accepted（已采纳，追认）**。该机制在认证服务账号注册与领域事件闭环落地时已生效，本文补齐架构决策记录。

## 背景

用户在平台注册账号后，需要通知下游服务（主要是 `user-service`）初始化用户公开基础资料（`user_profile`）及社交统计数据。在实现跨服务通信时，面临以下架构约束：

1. **分布式事务与网络一致性**：注册主流程在本地 MySQL 中写入 `auth_account`。若在业务本地事务中直接向 RabbitMQ 发送消息，网络抖动或 Broker 故障可能导致“事务已提交但消息丢失”，或者“事务回滚但消息已被消费”的幽灵账号问题。
2. **架构边界与服务自治**：认证服务核心职责是账号安全、凭据加密与会话池管理，严禁越权直接操作 `user_profile` 数据表；根据架构规则（`AGENTS.md`），微服务必须独占自属数据表，禁止跨服务访问 Mapper，也不得抽象共享业务持久化模块。
3. **注册链路可用性与时延**：账号注册是关键前台主链路，下游资料初始化耗时不应同步阻塞注册接口的 HTTP 响应。

## 决策

1. **自属 Transactional Outbox 模式**
   - 认证服务独占管理 `auth_outbox` 表，与 `auth_account` 在同一个本地数据库事务内原子写入。
   - 初始状态置为 `PENDING`，彻底消除跨网络双写不一致隐患，保证“只要账号落库成功，事件必已安全持久化”。
2. **事件契约与信封标准化**
   - Exchange：`media.platform.events`（Topic 类型，持久化）。
   - Routing Key：`auth.account.created.v1`，事件类型标识为 `auth.account.created`，契约版本为 1。
   - 采用平台统一信封格式 `EventEnvelope<AccountCreatedPayloadV1>`，包含 `eventId`、`eventType`、`eventVersion`、`occurredAt`、`sourceService` (`auth-service`)、`aggregateId` (`userId`)、`traceId` 与 `payload`。
   - 载荷仅包含下游建档所需的最小事实 `{ "userId": "...", "role": "user", "createdAt": "..." }`。严禁在消息中携带密码哈希、盐值、邮箱或会话凭据等高敏感认证数据。
3. **双通道投递调度**
   - **快速通道（低延迟）**：通过 Spring 事务同步器（`AfterCommitAuthOutboxDispatchNotifier`），在本地事务 `afterCommit` 成功后，通过 Java 21 虚拟线程 / 独立有界线程池异步唤醒 `AuthOutboxDispatcher` 立即执行派发，实现毫秒级下游响应。事务未提交或回滚时绝不触发。
   - **定时扫描通道（高可靠兜底）**：由 `AuthOutboxScanJob` 定时执行（默认每 3000ms），按批扫描到期未发送的 `PENDING` 或超时未完成的 `PROCESSING` 记录进行自愈补偿，防御服务崩溃、重启或瞬时网络异常。
4. **CAS 租约防重与 Broker Confirm 确认**
   - 快速通道与扫描通道均通过仓储 `AuthOutboxRepository.markClaimedIfEligible` 执行原子 CAS 条件更新，竞争认领记录并赋予唯一 `claimToken` 与租约到期时间，将状态跃迁为 `PROCESSING`。
   - 统一由 `AuthOutboxPublisher` 执行投递，要求等待 Broker Confirm ACK 且无 returned 异常。
   - 投递成功后，仅持有当前有效 `claimToken` 的实例方可调用 `markPublished` 将记录置为 `PUBLISHED` 并记录发布时间；若租约已过期被新线程接管，旧线程主动放弃回写，避免覆盖新租约。
5. **有界指数退避与终态收敛**
   - 投递失败时，以当前令牌计算有界指数退避时长（从 1s 递增至 300s，附带最多 20% 随机正向抖动防惊群），最多重试 20 次。
   - 达到最大重试上限后，记录由调度收敛标记为 `FAILED`，通过 Micrometer 暴露告警指标（`auth_outbox_failed_total`），等待人工对账或离线补偿。

## 备选方案

| 方案 | 不选的原因 |
| :--- | :--- |
| 注册事务内直接同步调用 RabbitMQ | 网络故障或 MQ 抖动会导致注册事务回滚，或产生网络超时导致的双写不一致；MQ 延迟直接拖垮注册主链路 |
| 注册成功后同步 HTTP/RPC 调用 user-service | 引入跨微服务强同步依赖，下游抖动直接导致注册失败，破坏服务自治与高可用 |
| 跨微服务抽象共享 Outbox 通用持久化模块 | 违反“服务只访问自属表”的架构边界，`common` 模块不得下沉业务表持久化逻辑 |

## 后果

- **收益**：
  - 账号注册链路与消息中间件强可用性解耦，网络中断不影响用户注册；
  - 领域事件具备 At-least-once 投递保障，配合 `traceId` 实现完整的审计与对账链路；
  - 双通道机制兼顾了正常场景下的毫秒级投递与异常场景下的自动容灾补偿。
- **代价与约束**：
  - `auth_outbox` 表随注册量持续增长，需保留定期归档或清理已发布历史记录的运维策略；
  - 异常重试可能导致重复投递，下游 `user-service` 消费者必须根据 `eventId` 严格实现防重幂等；
  - 从账号创建到下游资料就绪存在秒级（或毫秒级）异步最终一致性延迟。
- **与其他 ADR 的关系**：
  - 对比 [ADR 0001](0001-interaction-outbox-and-video-action-events.md)（互动自属 Outbox）：互动服务 Outbox 针对高频视频行为（点赞/收藏/播放），初期下游未就绪时默认关闭派发开关；认证服务 Outbox 面向低频、高关键度的账号生命周期事件，始终开启派发，且路由键保留显式版本后缀 `auth.account.created.v1`，直接驱动 `user-service` 的建档闭环。
