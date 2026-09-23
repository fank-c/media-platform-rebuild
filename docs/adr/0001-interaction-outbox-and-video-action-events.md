# ADR 0001: 互动模块 Transactional Outbox 机制与统一视频交互事件契约

## 状态
**Accepted（已采纳）**

## 背景与问题陈述
在平台重构架构中，`interaction-service` 作为视频高频交互行为（点赞、收藏、播放心跳、分享）的唯一管理者，需要向下游（如 `recommend-service` 推荐流与特征引擎）输出用户互动行为。
面临的核心技术挑战与约束包括：
1. **可靠投递与一致性**：高并发互动场景下，直接在 HTTP 请求或事务内同步向 RabbitMQ 投递存在丢事件、MQ 故障阻塞主业务、以及分布式事务不一致等风险。
2. **下游未就绪保护**：推荐服务当前尚未部署消费队列；若此时盲目向 RabbitMQ Topic 交换机投递，`mandatory: true` 将导致大量消息被退回或因无路由报错耗尽重试。
3. **真实动作与去重**：用户客户端存在网络重试、重复点赞、收藏夹之间移动/多收藏夹收录、频繁心跳上报等高频操作。如果不对动作做严格的去重和真实状态反转判定，会导致下游特征计算受到严重噪音污染。
4. **计数弱一致性**：公开统计计数（`interaction_video_counter`）采用 Redis 缓存配合定时批量异步刷盘（`VideoCounterFlushScheduler`），无法与数据库持久化明细和消息投递达成物理上的强原子一致。

## 架构决策

### 1. 采用自属 Transactional Outbox 发件箱模式
- `interaction-service` 独占自属发件箱表 `interaction_outbox`；
- 所有互动事件的记录与业务持久化事实（`interaction_like`、`interaction_star_item`、`interaction_watch_history`、`interaction_share_record`）严格在同一个本地 MySQL 事务内原子落库；
- 采用 CAS 状态流转（`PENDING` -> `PROCESSING` -> `PUBLISHED` / `FAILED`）与租约超时自愈机制，重试投递严格复用原 `eventId`，不强依赖 MQ 物理顺序。

### 2. 统一领域事件契约模板
- **Exchange**：`media.platform.events`（持久化 Topic 交换机）
- **Routing Key**：`interaction.video-action.v1`
- **信封结构**：
  ```json
  {
    "eventId": "32位无中划线UUID",
    "eventType": "interaction.video-action",
    "eventVersion": 1,
    "traceId": "链路追踪ID",
    "occurredAt": "ISO-8601 UTC时间戳",
    "payload": {
      "userId": "用户ID",
      "vid": "视频短码",
      "action": "LIKE | STAR | PLAY | SHARE",
      "state": "ACTIVE | INACTIVE"
    }
  }
  ```
- **动作枚举约束**：
  - `LIKE`：`ACTIVE`（点赞生效）/ `INACTIVE`（取消点赞生效）；
  - `STAR`：`ACTIVE`（全站首次收藏该视频）/ `INACTIVE`（从所有收藏夹彻底移出）；
  - `PLAY`：`ACTIVE`（观看时长达标且突破30分钟去重窗口的单次有效播放）；
  - `SHARE`：`ACTIVE`（通过请求级幂等校验的单次真实分享）。

### 3. 只记录真实动作变化与持久化防重依据
- **点赞**：仅当状态从无到有或从取消重新激活（`ACTIVE`），或从激活变为取消（`INACTIVE`）时建事件；重复点赞幂等忽略。
- **收藏**：先补齐按收藏夹删除时的 `userId` 归属校验，防止越权；以用户维度针对视频的“首度收藏”和“彻底清空”为准触发事件，用户跨收藏夹收录或在多个文件夹之间增删不产生虚假事件。
- **播放**：在 `interaction_watch_history` 表增加 `last_valid_play_at` 字段作为持久化防重依据，心跳累计时长达到 5 秒且距上次有效播放超过 30 分钟窗口时，在数据库事务内原子更新并写 Outbox，彻底摆脱纯 Redis 窗口在重启或故障时的弱防重缺陷。
- **分享**：接口强制要求客户端 Header 携带 `Idempotency-Key`，通过新增的 `interaction_share_record` 表进行持久化排他去重。超时重试请求幂等响应，不重复递增计数，不重复生成事件。

### 4. 先存储，后开放派发（受控演进策略）
- 发件箱运行配置中，`interaction.outbox.dispatch-enabled` 默认设为 `false`；
- 在当前阶段，所有真实互动事件均以 `PENDING` 状态安全持久化在 `interaction_outbox` 表中，不向 RabbitMQ 进行正式投递，防止无路由消费耗尽重试；
- 单元测试与集成测试中通过隔离测试队列闭环验证 Broker Confirm、状态回写与指数退避重试链路；
- 待下一阶段 `recommend-service` 正式上线消费队列与绑定后，通过配置平滑开启自动派发。

### 5. 计数弱一致性申明
- 现有公开统计计数（播放数、点赞数、收藏数、分享数）依赖 Redis 内存聚合与定时异步刷盘，属于最终一致性；
- 系统明确架构边界：**不能宣称公开计数、数据库明细事实与 Outbox 事件三者强原子一致**。

## 影响与后果
- **正面影响**：
  - 彻底解耦高并发互动与下游消费，保障互动主链路的高吞吐与零阻塞；
  - 彻底规避网络抖动与重试引发的事件与计数虚高；
  - 具备全量可重放、可审计的交互事件事实底座。
- **权衡与代价**：
  - 分享接口要求客户端必须提供 `Idempotency-Key` Header，老旧或未对齐客户端若缺失会收到 `400 Bad Request`；
  - 播放心跳在达成有效播放时需额外更新一行 `last_valid_play_at`，但仅在 30 分钟窗口首次达成时触发，开销可忽略。
