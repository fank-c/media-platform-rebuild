# 互动模块已知问题清单 · interaction-service

> 本文件记录互动模块的已知问题，随仓库版本管理。

- 核对基线：`2b56633 refactor: 简化视频元数据事件构建与消费逻辑`
- 核对方式：阅读源码与 Mapper SQL；**未编写复现用例**。标注"推断"的条目只做了代码推导，没有实际跑过。
- 优先级：**P0** = 数据错误或越权；**P1** = 统计/推荐信号失真，或错误码不对；**P2** = 边界问题、技术债。
- 状态：`待处理` / `待决策`（得先定规则才能改）/ `已解决`（已修复或被新架构取代）。
- **重大架构变更**：
  - 观看能力按 [ADR 0005](../adr/0005-观看能力拆分与视频时长本地快照.md) 拆表重构（进度/会话/凭据/快照分离），移除 Redisson 锁依赖。
  - 计数按 [ADR 0004](../adr/0004-interaction-counter-deltas.md) 改为事务内增量 + MySQL 后台汇总，停用 Redis 绝对值刷盘。
  - 收藏夹按 `ced0628` 增强了属主校验、重名防护、版本化防重与级联删除。
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
| INT-08 | P1 | 契约 | 分享幂等键冲突返回 500，不是 409 | 待处理 |
| INT-09 | P1 | 网关 | "匿名可访问"的查询接口实际被网关拦截，返回 401 | 待决策 |
| INT-10 | P2 | 语义 | 会话一直不断时，同一会话内也能触发 REPEAT 播放 | **已解决** |
| INT-11 | P2 | 语义 | 锁等待超时降级时，这次心跳的时长直接丢掉 | **已解决** |
| INT-12 | P2 | 可测性 | 实体和应用层各自取 `LocalDateTime.now()`，两个时间源 | 待处理 |
| INT-13 | P2 | 技术债 | 起播残留代码和 `PLAY_START` 常量没人用 | **已解决** |
| INT-14 | P2 | 功能缺口 | 收藏夹没有重名校验、改名和删除接口；GET 查询会写库 | **已解决** |
| INT-15 | P2 | 下游 | 推荐侧没有消费者，Outbox 只进不出，也没有清理策略 | 待处理 |

---

## P0（已解决）

### INT-01 收藏夹缺少属主校验，能读写他人收藏夹 ✅ 已解决

- **位置**：`StarApplicationService.resolveFolder()`、`StarApplicationService.getStarItems()`。
- **原问题**：`resolveFolder` 和 `getStarItems` 不比对 `userId`，传别人的 `folderId` 可越权读写。
- **解决方案**（`ced0628`）：
  - `resolveFolder` 和 `getStarItems` 统一增加 `folder.userId == 当前用户` 严格校验。
  - 不匹配时抛出"收藏夹不存在"异常，避免暴露他人收藏夹存在性。
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

## P1（部分已解决）

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

### INT-08 分享幂等键冲突返回 500，不是 409

- **位置**：`InteractionQueryApplicationService.recordShare()` 抛 `IllegalStateException`，`InteractionExceptionHandler` 没有对应处理。
- **现状**：同一个键被别的用户或别的视频用过时，落到兜底处理，返回 500，看起来像服务故障。
- **建议**：改抛 `InteractionException(HttpStatus.CONFLICT, ...)`。另外幂等键目前全局唯一，可以考虑按 `userId + key` 限定作用域。

### INT-09 "匿名可访问"的查询接口实际被网关拦截，返回 401

- **位置**：`gateway-service` 的 `AuthProperties.whitelist`（不含 `/api/interactions/**`）。
- **现状**：`/stat`、`/stats`、`/my-state`、`/watch-progress` 在服务内部允许匿名，但经过网关时游客一律 401。
- **待决策**：公开统计是否对游客开放？开放就要把 `GET /api/interactions/videos/*/stat` 等加入网关白名单，并同步 `docs/modules/gateway.md`；不开放就在服务侧统一 `requireUser`。这件事改变的是对外鉴权语义，需要你确认。

---

## P2（部分已解决）

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

- **位置**：`WatchHistory.create()` / `recordHeartbeat()` / `revive()` 自己取 `now()`，应用层又另取一个 `now` 传给会话判断和 CAS。
- **影响**：同一次心跳里 `lastWatchAt` 和 `last_valid_play_at` 可能差几毫秒；测试也没法控制时间。
- **建议**：注入 `Clock`（配置里已经有 `@ConditionalOnMissingBean(Clock.class)`），由应用层统一传入 `now`。

### INT-13 起播残留代码和 `PLAY_START` 常量没人用 ✅ 已解决

- **位置**：旧起播独立接口（已删除）。
- **原问题**：起播并入心跳后，`createForPlay()`、`recordPlayStart()`、`ACTION_PLAY_START` 成为死代码。
- **解决方案**（[ADR 0005](../adr/0005-观看能力拆分与视频时长本地快照.md) + `efe3bf2`）：
  - 删除旧 `interaction_watch_history` 表及相关实体、Mapper、领域方法。
  - 删除 `PLAY` / `PLAY_COMPLETE` / `PLAY_START` 三个 action，改用 `WATCH_VIEW_QUALIFIED` / `WATCH_COMPLETED`。

### INT-14 收藏夹没有重名校验、改名和删除接口；GET 查询会写库 ✅ 已解决

- **位置**：`StarApplicationService`、`InteractionStarController`。
- **原问题**：缺少重名校验、改名和删除功能；GET 查询有写副作用。
- **解决方案**（`ced0628`）：
  - **重名校验**：`createCustomFolder` 增加同名与系统保留名防护。
  - **改名接口**：`POST /star/folders/{folderId}/rename`，含重名校验。
  - **删除接口**：`DELETE /star/folders/{folderId}`，级联清理明细，彻底移除时联动计数/Outbox事件。
  - **惰性初始化分离**：默认收藏夹在首次收藏时创建，GET 查询改为纯读。

### INT-15 推荐侧没有消费者，Outbox 只进不出，也没有清理策略

- **现状**：
  - `dispatch-enabled=false`，所有事件都一直停在 `PENDING`。
  - `recommend-service` 还没有 `interaction.video-action` 的队列和消费者。
  - `interaction_outbox` 没有归档或清理任务，表会一直变大。
- **建议**：先做推荐侧的幂等消费者，再打开投递；同时补一个 `PUBLISHED` 记录的保留期清理任务。

---

## 其他（仓库级，非互动模块）

- 工作区有约 674 个文件处于修改状态，实际内容只差 `.gitignore` 一行，其余都是 CRLF/LF 换行差异（索引是 LF，工作区是 CRLF）。建议加 `.gitattributes` 或配置 `core.autocrlf`，避免真实改动被换行噪音淹没。
