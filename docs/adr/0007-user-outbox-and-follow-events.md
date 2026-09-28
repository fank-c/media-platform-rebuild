# ADR 0007: 用户模块自属 Outbox 与关注关系领域事件

## 状态

**Accepted（已采纳，追认）**。该机制在用户服务社交中枢落地事务发件箱与推荐互动事件标准化时已生效，本文补齐架构决策记录。

## 背景

关注与取消关注是平台用户社交关系网的核心。创作者关注关系的建立与解除，需要实时通知下游服务（特别是 `recommend-service`），用于更新用户协同兴趣偏好、驱动关注召回通道（`FollowingRecallChannel`）及作者冷启动曝光加权。在架构落地时面临以下约束：

1. **写操作强一致与消息可靠性**：关注关系与关注数/粉丝数在 `user-service` 本地事务中维护。若直接发送 RabbitMQ，网络瞬断或 Broker 异常会导致关注状态成功但下游未收到变更，破坏推荐召回的图谱一致性。
2. **防刷与噪音抑制**：客户端在网络弱网下易发生并发重试，用户也可能在短时间内频繁关注/取关。下游推荐画像对噪音极为敏感，若每次点击都产生事件，会迅速污染用户特征。必须确保只有持久化状态真实发生跃迁时才生成事件。
3. **交互契约标准化**：推荐服务需要接收包括视频交互（点赞、收藏等）与作者交互（关注、取关等）在内的多维行为输入，事件信封与载荷规范需保持统一。

## 决策

1. **自属 Transactional Outbox 架构**
   - 在用户服务自属数据库中建立 `user_outbox` 表，与关注关系记录（`user_follow`）及计数器变更在同一个 MySQL 本地事务内原子落库。
   - 严格遵循单一所有权边界，不依赖外部共享模块，不破坏服务自主性。
2. **对齐推荐互动流标准契约**
   - Exchange：`media.platform.events`（Topic 类型，持久化）。
   - Routing Key：`interaction.author-action.v1`，事件类型为 `interaction.author-action`，版本为 1。
   - 采用交互事件信封（包含 `eventId`、`eventType`、`eventVersion`、`traceId`、`occurredAt`），载荷 `AuthorActionPayload` 仅包含结构化行为事实：
     ```json
     {
       "userId": "u10001",
       "authorId": "u20002",
       "action": "FOLLOW",
       "state": "ACTIVE"
     }
     ```
     `action` 固定为 `FOLLOW`；`state` 为 `ACTIVE`（关注）或 `INACTIVE`（取消关注）。
3. **仅在状态真实跃迁时记录事件**
   - 在 `UserFollowApplicationService` 中，先依据数据库原子条件更新（`updateStatusConditionally`）判定状态是否发生有效跃迁（`0 -> 1` 或 `1 -> 0`）。
   - 对已处于关注状态的重复关注请求，或对已处于未关注状态的重复取关请求，直接幂等放行，绝不修改双方计数器，也绝不向 `user_outbox` 插入事件，从源头上遏制噪音。
4. **双通道投递与 CAS 租约并发控制**
   - **快速通道**：通过 `AfterCommitUserOutboxDispatchNotifier`，在本地事务 `afterCommit` 成功后，通过独立虚拟线程/线程池毫秒级唤醒 `UserOutboxDispatcher` 执行投递。
   - **自愈扫描**：`UserOutboxScanJob` 定时执行（默认每 5s），按批次扫描到期未发送的 `PENDING` 记录及租约超期的 `PROCESSING` 记录。
   - **CAS 租约防重**：快速通道与扫描通道均通过 `UserOutboxRepository.markClaimedIfEligible` 执行原子抢占并生成唯一 `claimToken`，在 Broker Confirm ACK 且无 returned 异常后由 `markPublished` 跃迁为 `PUBLISHED`。
5. **有界指数退避与终态收敛**
   - 投递失败时按指数退避递增等待时间（最多重试 20 次），超限后原子置为 `FAILED`，保留 `eventId` 用于后续人工对账与手动重放。

6. **推荐侧统一交互消费入口**
   - 视频互动与作者互动共用 `recommend-service.interaction-action.v1` 队列及 `InteractionEventConsumer`，分别绑定当前生产端路由 `interaction.video-action` 与 `interaction.author-action.v1`。
   - 仅依据 `eventType` 分发到视频、作者处理器，不保留旧消费者、队列别名或额外版本路由。现有版本标识不代表并行维护多个契约。
   - 作者事件在本地事务内写入消费幂等记录与反馈流水；`vid` 为空，`author_id` 指向作者。当前只记录事实，不维护关注关系副本，不参与关注召回，也不执行作者画像更新。
   - 直接维护空库建表脚本，不因本次开发中的设计替换增加兼容期或数据库升级流程。

## 备选方案

| 方案 | 不选的原因 |
| :--- | :--- |
| 关注本地事务内直接同步投递 RabbitMQ | 网络抖动或 Broker 故障将直接导致用户关注操作报错失败，严重损害前台社交主流程可用性 |
| 关注成功后同步 HTTP 调用推荐服务 | 破坏微服务自治，将弱相关的推荐画像链路引入关注强依赖，且下游短时不可用将引起连锁级联雪崩 |
| 复用通用的跨服务 Outbox 组件 | 违反“各服务独占自属数据表”的架构规范，将持久化机制与业务服务强行耦合在底层依赖库中 |

## 后果

- **收益**：
  - 用户关注主链路高可用，完全不受 RabbitMQ 网络与下游系统可用性影响；
  - 严格的状态跃迁校验杜绝了客户端抖动产生的无效事件，保障了下游推荐特征图谱的高纯净度；
  - 遵循统一信封规范，事件具备完整的溯源、重放与幂等消费基础。
- **代价与约束**：
  - 每次有效关注/取关均增加一次数据库 Outbox 写入，需要制定定时清理机制回收终态历史记录；
  - **当前消费端状态**：推荐侧已通过统一交互队列消费关注事件；发布端以 Broker Confirm 和 returned 状态判断投递结果。
- **与其他 ADR 的关系**：
  - 与 [ADR 0001](0001-interaction-outbox-and-video-action-events.md)（互动 Outbox）：互动 Outbox 针对视频维度行为（`interaction.video-action`，vid 聚合）；用户 Outbox 针对创作者社交维度行为（`interaction.author-action`，targetUserId 聚合），两者同构互补，共同构成推荐互动行为输入。
  - 与 [ADR 0006](0006-auth-outbox-and-account-created-event.md)（认证 Outbox）：认证 Outbox 驱动一对一的用户资料初始建档，而用户 Outbox 驱动高频多对多的推荐与社交图谱演进。
