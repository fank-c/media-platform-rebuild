# 互动 Outbox 发布与保留清理实施方案

## 方案状态

**代码已实现，完整回归与环境验收待完成**

### 当前验证记录（2026-09-29）

- 已实现独立清理链路、默认关闭配置、按发布时间的有界删除、清理指标、状态积压采样及两处建表索引。
- `./mvnw -pl service/interaction-service -am compile -q` 通过。
- 定向编译并运行清理服务、调度异常隔离、积压采样与配置/YAML 测试：11 例通过；本地 MySQL 集成测试 1 例未启用而跳过。
- 互动模块完整回归 `./mvnw -pl service/interaction-service -am test -q` 已通过（175 例，0 失败、0 错误、6 例跳过）；其中 Spring 调度注册测试验证了带单位与纯数字间隔。
- 真实 MySQL、派发与清理并发及 RabbitMQ 到推荐消费链路尚未验证。派发和清理默认值保持关闭，未修改实际数据库或启动基础设施。
- 本地 SQL 集成测试使用连接私有临时表，不删除已有业务数据。启用需设置 `INTERACTION_OUTBOX_CLEANUP_IT_ENABLED=true`，并提供 `INTERACTION_OUTBOX_CLEANUP_IT_DB_URL`（显式 UTC 时区）、`INTERACTION_OUTBOX_CLEANUP_IT_DB_USERNAME`、`INTERACTION_OUTBOX_CLEANUP_IT_DB_PASSWORD`，然后运行模块测试。
- 当前阶段同步维护空库建表入口；未确认需要保留升级既有数据库，因此不新增增量迁移脚本。

- 适用模块：`interaction-service`
- 方案类型：正式实施方案

## 背景

`interaction-service` 使用自属 `interaction_outbox` 记录点赞、收藏、分享和观看行为事件。当前业务事务会写入 Outbox，但：

1. `interaction.outbox.dispatch-enabled` 默认值为 `false`，事件只落库为 `PENDING`，不会发布到 RabbitMQ；
2. 推荐服务已经具备 `InteractionEventConsumer`、互动反馈处理和消费幂等记录，但是否发布仍由环境配置控制；
3. `interaction_outbox` 当前没有清理 `PUBLISHED` 记录的任务，长期运行会造成发件箱表持续增长；
4. `PENDING`、`PROCESSING` 和 `FAILED` 记录具有不同的可靠投递语义，不能用一个无差别的删除任务处理；
5. 表中已经存在 `published_at`，可以准确表达发布成功时间，不应使用业务发生时间或通用更新时间替代。

本文记录互动 Outbox 发布与保留清理的实施和验收要求。

## 实施方案

### 1. 保持事件发布默认关闭

- `interaction.outbox.enabled` 默认保持 `true`，业务真实状态变化继续在本地事务内写入 `interaction_outbox`；
- `interaction.outbox.dispatch-enabled` 默认保持 `false`，不因推荐消费者已经存在而修改代码默认值；
- 测试或明确启用的环境通过 `INTERACTION_OUTBOX_DISPATCH_ENABLED=true` 开启发布；
- `fast-dispatch-enabled` 不改变正式发布开关语义，必须同时满足总开关和派发开关才可发送。

默认关闭只表示暂不向 RabbitMQ 发布，不表示丢弃 Outbox 记录。`PENDING` 记录必须保留，直到后续环境显式开启派发或按环境生命周期进行人工处置。

### 2. 新增独立的 Outbox 清理任务

清理任务与派发扫描任务分离，新增独立的清理调度链路：

```text
InteractionOutboxCleanupJob
    -> InteractionOutboxCleanupService
        -> InteractionOutboxRepository
            -> InteractionOutboxMapper
```

清理任务只处理满足以下条件的记录：

```text
status = PUBLISHED
published_at < now - retention
```

不处理以下状态：

- `PENDING`：仍可能需要投递；
- `PROCESSING`：可能仍有在途投递，租约过期后由派发器重新认领；
- `FAILED`：保留故障证据，等待告警、人工排查、重放或归档决策；
- `PUBLISHED` 且 `published_at` 为空：保护异常数据，不自动删除。

### 3. 采用按发布时间的分批物理删除

清理截止时间以 `published_at` 为准，而不是 `occurred_at` 或 `updated_at`。每批删除使用稳定顺序：

```sql
DELETE FROM interaction_outbox
WHERE status = 'PUBLISHED'
  AND published_at < :cutoff
ORDER BY published_at, event_id
LIMIT :batchSize
```

单次调度限制最大批次数，使用独立短事务，避免一次清理长时间占用锁或造成数据库写入峰值。清理任务重复执行必须安全，删除失败不影响业务事务和事件派发。

### 4. 清理单独设置配置开关

拟增加以下配置，默认先关闭清理以便经过环境验证后再启用：

```yaml
interaction:
  outbox:
    cleanup-enabled: ${INTERACTION_OUTBOX_CLEANUP_ENABLED:false}
    retention: ${INTERACTION_OUTBOX_RETENTION:30d}
    cleanup-batch-size: ${INTERACTION_OUTBOX_CLEANUP_BATCH_SIZE:500}
    cleanup-max-batches: ${INTERACTION_OUTBOX_CLEANUP_MAX_BATCHES:10}
    cleanup-interval: ${INTERACTION_OUTBOX_CLEANUP_INTERVAL:1h}
```

`cleanup-enabled` 与 `dispatch-enabled` 不强制绑定：即使暂时关闭派发，也可以清理历史上已经成功发布且超过保留期的 `PUBLISHED` 记录；但清理任务绝不因此处理 `PENDING`。

### 5. 使用统一 Clock 和可观测性

- 清理截止时间使用注入的 `Clock` 计算，不直接调用 `Instant.now()` 或 `LocalDateTime.now()`；
- 增加清理执行次数、删除数量、耗时和失败次数指标；
- 监控 `PENDING`、`PROCESSING`、`FAILED` 的数量及最老记录年龄；
- `dispatch-enabled=false` 时，PENDING 增长属于配置结果，不应误报为发布故障，但仍应提供运行提示；
- `FAILED` 出现时保留记录并告警，不自动删除或无限重置为 `PENDING`。

### 6. 为清理查询增加专用索引

在 `interaction_outbox` 增加覆盖清理筛选和排序的索引：

```sql
(status, published_at, event_id)
```

初始化建表脚本和后续实际使用的数据库升级脚本必须同步维护。索引变更实施时需要结合工程现有数据库脚本约定确认交付文件。

## 备选方案

| 方案 | 不选的原因 |
| :--- | :--- |
| 修改代码默认值，将 `dispatch-enabled` 改为 `true` | 消费者存在不等于 RabbitMQ 拓扑、契约和下游处理已经通过环境验证；默认开启会扩大未验证链路的影响范围 |
| 按 `updated_at` 清理 | 重试、租约回收和状态更新会改变更新时间，不能准确表示成功发布后的保留起点 |
| 按 `occurred_at` 清理 | 业务发生时间可能早于实际发布时间，刚发布的旧事件可能被立即删除 |
| 清理所有超过期限的 Outbox 记录 | 会删除尚未投递的 `PENDING` 或仍可能恢复的 `PROCESSING`，破坏至少一次投递语义 |
| 自动删除 `FAILED` | 丢失故障证据，且没有确认下游是否需要重放或人工处置 |
| 将清理逻辑并入派发扫描任务 | 派发和保留清理的状态、故障边界及运行开关不同，异常相互影响且难以独立验证 |

## 后果

### 收益

- 互动业务默认仍保持“先落 Outbox、后决定是否发布”的安全边界；
- 推荐侧可通过环境变量显式开启事件发布，不需要改代码默认值；
- `PUBLISHED` 记录不会永久增长；
- `PENDING`、`PROCESSING`、`FAILED` 不会被清理任务误删；
- 清理任务可以独立限速、监控和暂停；
- `published_at` 与专用索引支持可解释、可重试的清理。

### 代价

- Outbox 表需要额外索引和分批删除写入；
- `FAILED` 记录暂时持续保留，需要后续人工处置或归档策略；
- 默认关闭派发时，`PENDING` 仍会增长，必须通过环境监控识别配置造成的堆积；
- 事件发布和清理需要分别进行单元测试、数据库集成测试及环境验证。

## 验证与回退

### 验证

1. 默认配置下 `dispatch-enabled=false`，业务操作仍生成 `PENDING` Outbox；
2. 设置 `INTERACTION_OUTBOX_DISPATCH_ENABLED=true` 后，事件可以进入推荐队列并由 `eventId` 幂等消费；
3. 设置 `INTERACTION_OUTBOX_CLEANUP_ENABLED=true` 后，只删除 `PUBLISHED` 且 `published_at` 严格早于截止时间的记录；
4. `PENDING`、`PROCESSING`、`FAILED` 和 `published_at IS NULL` 的记录均不被删除；
5. 单批删除不超过 `cleanup-batch-size`，单次调度不超过 `cleanup-max-batches`；
6. 清理任务与派发任务并发运行时，不会删除新发布记录；
7. 使用固定 `Clock` 验证截止时间边界和任务重试行为；
8. 清理异常只产生日志和指标，不影响互动业务事务。

### 回退

- 通过 `INTERACTION_OUTBOX_CLEANUP_ENABLED=false` 停止清理，不删除任何数据；
- 通过 `INTERACTION_OUTBOX_DISPATCH_ENABLED=false` 暂停 RabbitMQ 发布，已落库的 `PENDING` 保留；
- 已删除的 `PUBLISHED` 记录不能从数据库恢复，因此实施前必须确认保留期、备份和审计要求；
- 不允许通过重新开启旧的绝对值计数刷盘机制来处理 Outbox 问题。

## 修订记录

- 2026-09-27：提出互动 Outbox 发布开关和发布记录保留清理方案；确认推荐侧消费者已实现，但事件发布默认仍由环境配置关闭。
