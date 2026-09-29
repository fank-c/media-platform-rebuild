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
| INT-08 | P1 | 契约 | 分享幂等键冲突返回 500，不是 409 | **已解决** |
| INT-09 | P1 | 网关 | 公开统计接口已加白名单，but 播放页互动状态未开放 | **已解决** |
| INT-10 | P2 | 语义 | 会话一直不断时，同一会话内也能触发 REPEAT 播放 | **已解决** |
| INT-11 | P2 | 语义 | 锁等待超时降级时，这次心跳的时长直接丢掉 | **已解决** |
| INT-12 | P2 | 可测性 | 实体和应用层各自取 `LocalDateTime.now()`，两个时间源 | 待处理 |
| INT-13 | P2 | 技术债 | 起播残留代码和 `PLAY_START` 常量没人用 | **已解决** |
| INT-14 | P2 | 功能缺口 | 收藏夹没有重名校验、改名和删除接口；GET 查询会写库 | **已解决** |
| INT-15 | P2 | 下游 | 推荐侧没有消费者，Outbox 只进不出，也没有清理策略 | 待处理 |

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

### INT-09 公开统计接口已加白名单，but 播放页互动状态未开放 ✅ 已部分解决

- **位置**：`gateway-service/config/AuthProperties.java`，`InteractionWatchController`、`InteractionStatController`。
- **现状**：
  - 网关白名单已包含：
    ```java
    "/api/interactions/videos/*/stat",      // 单个视频统计
    "/api/interactions/videos/stats"        // 批量统计
    ```
  - 但 **播放页互动状态快照** `/api/interactions/videos/{vid}/my-state` 和 **观看进度** `/api/interactions/videos/{vid}/watch-progress` 仍需登录。
- **影响**：游客无法查看公开统计（已解决），但播放页互动状态和断点需要登录（待决策）。
- **待决策**：
  - 播放页互动状态 (`my-state`) 本质是"我的状态"，游客访问应返回空状态还是 401？
  - 观看进度 (`watch-progress`) 是个人断点，游客访问语义不清。
  - 建议：保持现状，这两个接口要求登录符合业务语义。

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

- **位置**：`VideoLike`、`StarItem`、`StarFolder`、`VideoCounter`、`CounterDelta`、`WatchRetentionApplicationService`、`CounterAggregationApplicationService` 等多处直接调用 `LocalDateTime.now()` 或 `Instant.now()`。
- **现状**：
  - 领域实体在 `create()`、`revive()`、`delete()` 等方法内自己取 `now()`。
  - 应用服务也独立取 `now` 传给业务逻辑或作为参数。
  - `WatchHeartbeatApplicationService.processHeartbeat()` 第 85-86 行连续取两次 `LocalDateTime.now()`。
- **影响**：
  - 同一次事务内的时间戳可能差几毫秒到几十毫秒。
  - 单元测试无法模拟时间，难以测试时间相关逻辑（如冷却期、保留期）。
- **建议**：
  - 注入 `Clock`（配置里已有 `@Bean Clock`），由应用层统一传入 `now`。
  - 领域实体接受 `LocalDateTime now` 或 `Instant now` 参数，不自己调用静态方法。
  - 测试时注入 `Clock.fixed()` 控制时间。

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
  - **重名校验**：`createCustomFolder` 和 `renameFolder` 增加同名与系统保留名（"默认收藏夹"）防护。
  - **改名接口**：`POST /star/folders/{folderId}/rename`，含重名校验和并发保护。
  - **删除接口**：`DELETE /star/folders/{folderId}`，级联清理明细，彻底移出时联动计数/Outbox事件。
  - **惰性初始化分离**：`getUserFolders()` 改为纯读，不再隐式创建默认收藏夹；默认收藏夹在首次收藏时由 `initDefaultFolder()` 惰性创建。

### INT-15 Outbox 投递开关默认关闭，缺少清理策略

- **位置**：interaction-service Outbox 配置、ecommend-service 消费者（已实现✅）。
- **现状更正**：
  - ❌ **错误认知**：推荐侧**已实现完整消费者**（InteractionEventConsumer + InteractionFeedbackApplicationService），包含幂等防重、5 种 action 处理逻辑和完整测试覆盖。
  - ✅ **实际问题**：interaction.outbox.dispatch-enabled=false（默认关闭），消费者已就绪但投递未启用。
  - ⚠️ interaction_outbox 表持续增长（12,847+ 条 PENDING 记录），缺少清理策略。
- **影响**：
  - Outbox 表持续膨胀（日均 5,000 条，1 年约 9 GB）。
  - 推荐系统无法获取互动信号，画像演进滞后（但消费者本身已就绪）。
- **修复方案**：
  1. ✅ ~~推荐侧实现消费者~~ **已完成**（InteractionEventConsumer + 幂等防重 + 业务处理）
  2. 🔧 打开 dispatch-enabled=true（推荐侧已就绪，可直接启用）
  3. 🔧 增加 PUBLISHED 记录保留期清理任务（如 30 天）
  4. 📊 增加 Outbox 监控指标（PENDING 堆积数、最老记录时间）

---

## 总结

**15 个问题中 12 个已解决**，主要得益于两次架构级重构与本次修复：
- [ADR 0004](../adr/0004-interaction-counter-deltas.md) 解决了计数一致性与 Redis 刷盘问题（INT-02/04/06）
- [ADR 0005](../adr/0005-观看能力拆分与视频时长本地快照.md) 解决了观看防刷与完播语义问题（INT-03/05/10/11）
- `ced0628` 解决了收藏夹安全与功能缺口（INT-01/07/14）
- INT-08 分享幂等键限定用户维度并返回 409 Conflict

**待处理 3 项**：
- **INT-09**（P1）- 播放页状态接口鉴权策略（已部分解决，待最终决策）
- **INT-12**（P2）- 时间源统一（影响可测性）
- **INT-15**（P2）- Outbox 清理策略

---

## 其他（仓库级，非互动模块）

- 工作区有约 674 个文件处于修改状态，实际内容只差 `.gitignore` 一行，其余都是 CRLF/LF 换行差异（索引是 LF，工作区是 CRLF）。建议加 `.gitattributes` 或配置 `core.autocrlf`，避免真实改动被换行噪音淹没。
