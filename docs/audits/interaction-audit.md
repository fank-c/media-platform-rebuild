# 互动模块问题审计记录 · interaction-service

> 本文件保留问题证据、历史修复与剩余风险。当前执行事项以 [TODO](../TODO.md#interaction-service) 为准。

- 核对基线：`5014f78 feat(interaction): 实现 Outbox 已发布记录保留清理`
- 核对方式：阅读源码、Mapper SQL及已有测试；本文件记录问题闭环和剩余验收项，不替代完整模块验收报告。
- 优先级：**P0** = 数据错误或越权；**P1** = 统计/推荐信号失真，或错误码不对；**P2** = 边界问题、技术债。
- 状态：`待处理` / `待决策` / `已实现待验收` / `已解决`。`已实现待验收` 表示代码已落地，但仍有模块回归、真实基础设施或契约联调未完成；`待决策` 表示实现方向或环境开关尚未确认。
- **重大架构变更**：
  - 观看能力按 [ADR 0005](../adr/0005-观看能力拆分与视频时长本地快照.md) 拆表重构（进度/会话/凭据/快照分离），移除 Redisson 锁依赖。
  - 计数按 [ADR 0004](../adr/0004-interaction-counter-deltas.md) 改为事务内增量 + MySQL 后台汇总，停用 Redis 绝对值刷盘。
  - 互动 Outbox 已增加独立的 `PUBLISHED` 保留清理任务，但派发与清理默认仍关闭，真实 RabbitMQ、推荐消费和 MySQL 联调需要单独验收。
  - 文中 `WatchHistory`、`interaction_watch_history`、`PLAY_COMPLETE`、`VideoCounterRedisCache`、`VideoCounterFlushScheduler` 等位置指向重构前实现，仅作审计记录。

## 总览

| 编号 | 优先级 | 分类 | 标题 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| INT-01 | P0 | 安全 | 收藏夹缺少属主校验，能读写他人收藏夹 | **已解决** |
| INT-02 | P0 | 计数 | 缓存过期后单字段 Hash 被当成完整快照，刷盘覆盖丢数 | **已解决** |
| INT-03 | P1 | 防刷 | 心跳的增量、视频时长和进度都信客户端 | **已解决** |
| INT-04 | P1 | 一致性 | 点赞/收藏/分享在事务内写 Redis 计数，回滚后计数偏大 | **已解决** |
| INT-05 | P1 | 语义 | 完播防重不一致：删历史能重复完播，正常重看永远不再完播 | **已解决** |
| INT-06 | P1 | 计数 | 刷盘失败丢脏标记；降级到本机内存后多实例计数分裂 | **已解决** |
| INT-07 | P1 | 并发 | 首次点赞/收藏/分享先查后写，并发时 500 或重复计数（推断） | **已解决** |
| INT-08 | P1 | 契约 | 分享幂等键冲突返回 500，不是 409 | **已解决** |
| INT-09 | P1 | 网关/契约 | 服务内匿名回退与网关游客访问边界 | **待决策** |
| INT-10 | P2 | 语义 | 会话一直不断时，同一会话内也能触发 REPEAT 播放 | **已解决** |
| INT-11 | P2 | 语义 | 锁等待超时降级时，这次心跳的时长直接丢掉 | **已解决** |
| INT-12 | P2 | 可测性 | 实体和应用层各自取 `LocalDateTime.now()`，两个时间源 | **已实现待验收** |
| INT-13 | P2 | 技术债 | 起播残留代码和 `PLAY_START` 常量没人用 | **已解决** |
| INT-14 | P2 | 功能缺口 | 收藏夹没有重名校验、改名和删除接口；GET 查询会写库 | **已解决** |
| INT-15 | P2 | 下游/运维 | Outbox 派发默认关闭，已发布记录清理需完成环境验收 | **已实现待验收/待决策** |

---

## P0（已解决）

### INT-01 收藏夹缺少属主校验，能读写他人收藏夹 ✅ 已解决

- **位置**：`StarApplicationService.requireOwnedFolder()`、`getStarItems()`。
- **原问题**：`resolveFolder` 和 `getStarItems` 不比对 `userId`，传别人的 `folderId` 可越权读写。
- **解决方案**（`ced0628`）：
  - 新增 `requireOwnedFolder(userId, folderId)` 私有方法，统一校验 `folder.userId == 当前用户 && isActive`。
  - `starVideo`、`unstarVideo`、`getStarItems`、`renameFolder`、`deleteFolder` 全部调用该方法校验。
  - 不匹配时抛出 `IllegalArgumentException("收藏夹不存在")`，避免暴露他人收藏夹存在性。
  - 补充越权场景单测。

### INT-02 缓存过期后单字段 Hash 被当成完整快照，刷盘覆盖丢数 ✅ 已解决

- **位置**：旧 `VideoCounterRedisCache`、`VideoCounterFlushScheduler`（已删除）。
- **原问题**：缓存过期后 HINCRBY 重建单字段 Hash，刷盘时用绝对值覆盖 DB，导致其他计数归零。
- **解决方案**（[ADR 0004](../adr/0004-interaction-counter-deltas.md)）：
  - 停用 Redis 绝对值快照刷盘机制。
  - 改为**事务内增量 + MySQL 后台汇总**：业务变更同事务写入 `interaction_counter_delta`，后台定时汇总到 `interaction_video_counter`。
  - 增量表建立 `uk_counter_delta_source (source_type, source_id)` 唯一约束，物理防重。
  - 汇总使用 `GREATEST(0, counter + delta)` 防止负数下溢。

---

## P1（已解决与待决策）

### INT-03 心跳的增量、视频时长和进度都信客户端 ✅ 已解决

- **位置**：旧 `WatchHeartbeatApplicationService`、`WatchHistory`（已重构）。
- **原问题**：`deltaDuration` 只限单次上限，无服务端时间校验；`videoDuration` 信客户端；完播只看位置不看时长。
- **解决方案**（[ADR 0005](../adr/0005-观看能力拆分与视频时长本地快照.md)）：
  - **服务端时间三重校验**：增量不超过服务端时间差、单次上限、会话剩余时长。
  - **视频时长本地快照**：消费 `content.video.metadata` 事件，维护 `interaction_video_snapshot`，不做跨服务同步调用。
  - **完播双 90% 门槛**：位置 ≥ 90% **且** 有效观看时长 ≥ 90% 视频时长。
  - **观看凭据防重表**：`interaction_watch_event_claim (user_id, video_id, event_type)` 唯一键，按会话抢占。

### INT-04 点赞/收藏/分享在事务内写 Redis 计数，回滚后计数偏大 ✅ 已解决

- **位置**：旧 `VideoCounterRepository`（已删除）。
- **原问题**：事务内 HINCRBY，事务回滚时 Redis 计数不回滚。
- **解决方案**（[ADR 0004](../adr/0004-interaction-counter-deltas.md)）：
  - 停用 Redis 写缓冲，改为事务内写增量到 `interaction_counter_delta`，与业务状态强一致。
  - 后台汇总按 `FOR UPDATE` 行锁 + 批量求和 + 原子更新快照。

### INT-05 完播防重不一致：删历史能重复完播，正常重看永远不再完播 ✅ 已解决

- **位置**：旧 `WatchHistory.completed` 字段（已删除）。
- **原问题**：删除后复活清零 `completed`，可重复完播；未删除记录完播只能置位一次。
- **解决方案**（[ADR 0005](../adr/0005-观看能力拆分与视频时长本地快照.md)）：
  - **统一完播语义**：按观看会话计，每会话最多一次 `WATCH_COMPLETED`，不增加播放量。
  - 完播凭据存入 `interaction_watch_event_claim`，删除进度不释放凭据，删了重看不重置冷却。

### INT-06 刷盘失败丢脏标记；降级到本机内存后多实例计数分裂 ✅ 已解决

- **位置**：旧 `VideoCounterRedisCache`、`VideoCounterFlushScheduler`（已删除）。
- **原问题**：先 SPOP 后刷盘，失败后标记丢失；Redis 异常降级到本地内存，多实例计数分裂。
- **解决方案**（[ADR 0004](../adr/0004-interaction-counter-deltas.md)）：
  - 停用 Redis 刷盘机制，增量持久化在 MySQL，不存在脏标记丢失问题。
  - 增量汇总失败整批回滚，下次扫描重试。

### INT-07 首次点赞/收藏/分享先查后写，并发时 500 或重复计数（推断） ✅ 已解决

- **位置**：`LikeApplicationService`、`StarApplicationService`。
- **原问题（推断）**：并发首次操作时唯一键冲突抛 `DuplicateKeyException`，返回 500。
- **解决方案**（`ced0628`）：
  - **实体版本化防重**：`interaction_like.version`、`interaction_star_item.version` 单调递增。
  - **增量来源标识版本化**：`like:{likeId}:v{version}`、`star_item:{itemId}:v{version}`。
  - `uk_counter_delta_source` 唯一约束防止重复记账，允许合法状态往返，阻断重试重复。
  - 并发创建默认收藏夹时，捕获 `DuplicateKeyException` 后用 `FOR UPDATE` 当前读回退。

### INT-08 分享幂等键冲突返回 500，不是 409 ✅ 已解决

- **位置**：`InteractionQueryApplicationService.recordShare()`。
- **原因**：
  - 幂等键原先在表层为全局唯一键 `uk_share_idempotency`，不同用户生成同名键产生冲突；
  - 增量表 `interaction_counter_delta` 原使用 `idempotencyKey` 作为 `source_id`，同样存在跨用户唯一键冲突；
  - 相同用户不同视频复用幂等键时抛出 `IllegalStateException`，被全局异常处理器捕获返回 500。
- **解决方案**：
  1. 表结构调整为用户联合唯一键 `uk_share_user_idempotency (user_id, idempotency_key)`；
  2. 仓储层提供 `findByUserIdAndIdempotencyKey(userId, idempotencyKey)`；
  3. 增量表流水来源修改为 `"share:" + userId + ":" + key`，避免增量流水全局键碰撞；
  4. 同一用户复用键请求不同视频时，抛出 `InteractionException(HttpStatus.CONFLICT, "幂等键已被用于其他分享请求")`，映射为 HTTP 409；
  5. 增加并发插入捕获 `DuplicateKeyException` 并二次回退校验幂等的竞态防护。

### INT-09 服务内匿名回退与网关游客访问边界

- **证据**：网关 `AuthProperties` 默认白名单包含公开统计，但没有 `my-state` 和 `watch-progress`。
- **当前边界**：这两个接口经网关仍要求登录；互动服务内部允许匿名返回默认零值，不能据此宣称客户端游客可经网关访问。
- **剩余决策**：是否开放这两个个人状态接口，需要明确产品契约后同步网关配置与验证；本轮不修改鉴权。

---

## P2（历史修复与待验收）

### INT-10 会话一直不断时，同一会话内也能触发 REPEAT 播放 ✅ 已解决

- **位置**：旧 `WatchHistory` 会话逻辑（已重构）。
- **原问题**：当前会话达标后，冷却期过后在同一会话内触发 REPEAT，违背"新会话"条件。
- **解决方案**（[ADR 0005](../adr/0005-观看能力拆分与视频时长本地快照.md)）：
  - **会话隔离**：起播显式开启新会话（`sequence=0`），传 `Idempotency-Key`，活跃会话返回 409。
  - **起播计数解耦**：起播立即记录播放量（需通过 6h 冷却），不依赖时长门槛。
  - **有效观看独立**：心跳达标 `max(5秒, 30%时长)` 发 `WATCH_VIEW_QUALIFIED`，不重计播放量。

### INT-11 锁等待超时降级时，这次心跳的时长直接丢掉 ✅ 已解决

- **位置**：旧 Redisson 锁降级逻辑（已删除）。
- **原问题**：锁超时降级只读返回，`deltaDuration` 丢失。
- **解决方案**（[ADR 0005](../adr/0005-观看能力拆分与视频时长本地快照.md)）：
  - **移除 Redisson 分布式锁**，改用 MySQL 行级锁 + CAS 乐观锁。
  - 心跳按 `sequence` 递增，重复与乱序请求幂等返回只读回执。

### INT-12 实体和应用层各自取 `LocalDateTime.now()`，两个时间源

- **位置**：`VideoLike`、`StarItem`、`StarFolder`、`VideoCounter`、`CounterDelta`、`WatchRetentionApplicationService`、`CounterAggregationApplicationService` 等多处直接调用 `LocalDateTime.now()` 或 `Instant.now()`。
- **原实现**：
  - 领域实体在 `create()`、`revive()`、`delete()` 等方法内自己取 `now()`。
  - 应用服务也独立取 `now` 传给业务逻辑或作为参数。
  - `WatchHeartbeatApplicationService.processHeartbeat()` 第 85-86 行连续取两次 `LocalDateTime.now()`。
- **影响**：
  - 同一次事务内的时间戳可能差几毫秒到几十毫秒。
  - 单元测试无法模拟时间，难以测试时间相关逻辑（如冷却期、保留期）。
- **实施进展（已实现待验收）**：最新实现提交 `531bf23` 已按方案 B 收敛互动生产代码中的静态当前时间调用，领域方法显式接收 UTC 时间；观看心跳、计数、收藏、点赞、分享及元数据快照使用统一时钟。
  - 生产代码静态搜索已不再发现裸 `LocalDateTime.now()`、`Instant.now()` 或 `now(ZoneOffset.UTC)`。
  - 相关单元测试和持久化时间契约测试已随实现提交；仍需完成互动模块完整回归及本地 MySQL 集成验证后关闭本问题。
  - 当前时间源约定见[互动模块](../modules/interaction.md#8-时间源约定)，未完成事项以 TODO 为准。

### INT-13 起播残留代码和 `PLAY_START` 常量没人用 ✅ 已解决

- **位置**：旧起播独立接口（已删除）。
- **原问题**：起播并入心跳后，`createForPlay()`、`recordPlayStart()`、`ACTION_PLAY_START` 成为死代码。
- **解决方案**（[ADR 0005](../adr/0005-观看能力拆分与视频时长本地快照.md) + `efe3bf2`）：
  - 删除旧 `interaction_watch_history` 表及相关实体、Mapper、领域方法。
  - 删除 `PLAY` / `PLAY_COMPLETE` / `PLAY_START` 三个 action，改用 `WATCH_VIEW_QUALIFIED` / `WATCH_COMPLETED`。

### INT-14 收藏夹能力和查询副作用 ✅ 已解决

- **位置**：`StarApplicationService`、`InteractionStarController`。
- **原问题**：缺少重名校验、改名和删除功能；GET 查询有写副作用。
- **解决方案**（`ced0628`）：
  - **重名校验**：`createCustomFolder` 和 `renameFolder` 增加同名与系统保留名（"默认收藏夹"）防护。
  - **改名接口**：当前契约为 `PUT /star/folders/{folderId}`，含重名校验和并发保护。
  - **删除接口**：`DELETE /star/folders/{folderId}`，级联清理明细，彻底移出时联动计数/Outbox 事件。
  - **惰性初始化分离**：`getUserFolders()` 改为纯读，不再隐式创建默认收藏夹；默认收藏夹在首次收藏时由 `initDefaultFolder()` 惰性创建。

### INT-15 Outbox 派发开关与已发布记录清理

- **位置**：`interaction-service` Outbox 配置、`recommend-service` 消费者、Outbox 清理调度链路。
- **当前事实**：
  - 推荐侧已实现统一 `InteractionEventConsumer`、互动反馈处理、消费幂等记录和相关测试；“推荐侧没有消费者”已不是当前问题。
  - `interaction.outbox.dispatch-enabled=false` 是明确的默认配置。它表示事件先落库为 `PENDING`，不表示事件丢弃。
  - `PUBLISHED` 记录的独立保留清理任务已在 `5014f78` 中实现，默认 `cleanup-enabled=false`，按 `published_at` 分批清理，不处理 `PENDING`、`PROCESSING`、`FAILED` 或发布时间为空的记录。
- **剩余事项**：
  1. **环境决策**：确认 RabbitMQ 拓扑、互动事件契约和推荐消费链路完成联调后，再通过 `INTERACTION_OUTBOX_DISPATCH_ENABLED=true` 开启派发；不建议仅因为消费者已存在就修改代码默认值。
  2. **验收**：完成真实 MySQL、RabbitMQ 到推荐消费链路，以及派发和清理并发行为验证。
  3. **运维**：持续监控 `PENDING`、`PROCESSING`、`FAILED` 数量和最老记录年龄；默认关闭派发时，`PENDING` 增长属于配置结果，应单独提示而不是误报为派发故障。
- **实现说明**：[互动模块投递链路](../modules/interaction.md#52-投递链路)。

---

## 总结

当前清单的 15 项历史问题中：

- **12 项已解决**：INT-01 至 INT-08、INT-10、INT-11、INT-13、INT-14；
- **1 项待决策**：INT-09，个人状态接口的网关游客访问策略；
- **1 项已实现待验收**：INT-12，剩余完整回归和本地 MySQL 集成验证；
- **1 项已实现但仍有环境决策与联调要求**：INT-15，派发默认关闭是配置决策，清理链路已实现。

主要架构闭环：
- [ADR 0004](../adr/0004-interaction-counter-deltas.md) 解决计数一致性与 Redis 刷盘问题（INT-02/04/06）；
- [ADR 0005](../adr/0005-观看能力拆分与视频时长本地快照.md) 解决观看防刷与完播语义问题（INT-03/05/10/11）；
- `ced0628` 解决收藏夹安全、并发和功能缺口（INT-01/07/14）；
- `ed19574` 修复分享幂等键冲突及并发返回语义（INT-08）；
- `531bf23` 完成互动模块时间源统一实现（INT-12，待验收）；
- `5014f78` 完成 Outbox 已发布记录保留清理实现（INT-15，待环境验收）。

当前执行事项统一维护在 [TODO](../TODO.md#interaction-service)。本文件保留问题证据，不重复承担执行看板职责。
