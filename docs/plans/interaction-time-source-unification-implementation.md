# 互动时间源统一实施方案

## 方案状态

**待实施**

- 关联问题：INT-12（互动实体和应用层各自取系统时间）
- 适用模块：`interaction-service`
- 方案类型：正式实施方案
- 实施路线：**方案 B——全模块统一时间依赖**

## 1. 背景与目标

当前 `interaction-service` 的领域实体、应用服务、定时任务和部分持久化转换代码分别调用 `LocalDateTime.now()` 或 `Instant.now()`。同一个用例内可能出现多个时间值，测试也无法稳定控制时间边界。

已存在的 `Clock` Bean 目前只被 Outbox 等部分组件使用，尚未成为整个互动模块的时间依赖约束。

本方案的目标是：

1. 互动模块所有“当前时间”都通过统一的 `Clock` 获取；
2. 一个应用用例在入口只读取一次当前时间，并向下传递；
3. 领域对象的创建、恢复、删除和状态更新方法显式接收时间参数，不再自行调用静态 `now()`；
4. 定时任务、汇总、保留期清理和 Outbox 租约使用同一时间抽象；
5. 统一 UTC 时间基准，避免机器默认时区造成行为差异；
6. 通过 `Clock.fixed()` 稳定测试冷却期、会话超时、保留期和边界时刻。

本方案只处理时间来源和时间传递，不改变互动业务规则、数据库字段语义、HTTP 契约或事件契约。

## 2. 已确认的范围

### 2.1 需要改造的代码类别

- 领域模型：
  - `VideoLike`
  - `StarFolder`
  - `StarItem`
  - `VideoCounter`
  - `CounterDelta`
  - `InteractionShareRecord`
  - 其他后续发现的业务实体和值对象工厂
- 应用服务：
  - `WatchHeartbeatApplicationService`
  - `WatchRetentionApplicationService`
  - `CounterAggregationApplicationService`
  - `VideoMetadataApplicationService`
  - `LikeApplicationService`
  - `StarApplicationService`
  - `InteractionQueryApplicationService`
  - 其他实际产生或更新业务时间的用例服务
- 基础设施：
  - `InteractionOutboxRepository` 及 Outbox 调度链路
  - `CounterDeltaRepositoryImpl`
  - `InteractionShareRecordPO`
  - 其他在持久化转换失败时自行补当前时间的代码
- 测试：
  - 领域模型单测
  - 应用服务单测
  - 定时任务测试
  - Outbox、观看心跳和计数汇总集成测试

### 2.2 不在本次范围内的时间

以下时间不属于本次业务时间源改造目标，但必须保持语义清晰：

- 客户端请求中的 `occurredAt`：它是外部输入，不能作为服务端当前时间；
- 数据库 `CURRENT_TIMESTAMP` 默认值：只允许作为数据库兜底，业务关键时间仍由应用显式写入；
- 日志框架、消息 Broker 或 HTTP 服务器自身的系统时间；
- 仅用于展示的日期格式转换；
- 其他服务模块的时间源，除非后续单独立项。

## 3. 时间约定

### 3.1 统一使用 UTC

互动服务的 `Clock` Bean 固定使用：

```java
Clock.systemUTC()
```

所有需要当前时间的应用服务按以下规则取得时间：

```java
Instant now = clock.instant();
LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
```

同一条调用链只能选定一种领域时间类型，不允许为了方便在中途隐式使用系统默认时区转换。

### 3.2 时间类型选择

采用以下边界约定：

| 场景 | 推荐类型 | 原因 |
|---|---|---|
| Outbox、分享事件和跨边界事件时间 | `Instant` | 明确表示绝对时间，避免时区歧义 |
| 现有 MySQL `DATETIME(3)` 业务字段 | `LocalDateTime`（按 UTC 解释） | 保持现有表结构和 Mapper 契约不变 |
| 时长、超时、保留期 | `Duration` | 表达时间间隔，不参与时区换算 |
| 客户端日期输入 | 现有 API 类型 | 先按接口契约解析，不替代服务端当前时间 |

本次不做数据库字段整体改为 `TIMESTAMP` 或 `Instant` 的迁移。`LocalDateTime` 持久化字段必须统一按 UTC 生成和读取。

### 3.3 一次用例只取一次当前时间

应用服务入口取得的 `now` 是该次用例的业务时间快照：

```java
LocalDateTime now = utcNow();
// 同一事务内所有领域变更、状态判断和事件时间使用 now
```

如一个流程明确需要两个不同时间点（例如等待外部操作后再次判断），必须显式命名并说明原因，不能通过多次无标识的 `now()` 隐式产生时间差。

## 4. 设计方案

### 4.1 Clock Bean 作为唯一系统时间入口

保留现有配置类提供的 `Clock` Bean，并明确其职责：

- 生产环境使用 `Clock.systemUTC()`；
- 测试通过 Spring Bean 或构造器注入 `Clock.fixed(...)`；
- 不在领域模型中注入 `Clock`；
- 不在持久化实体或 Mapper 中读取系统时间；
- 不允许新增裸调用 `LocalDateTime.now()`、`LocalDateTime.now(zone)` 或 `Instant.now()`。

建议在互动服务内提供统一的小型时间转换工具或应用层基类方法，例如：

```java
static LocalDateTime utcLocalDateTime(Clock clock) {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
}
```

该工具只能负责转换，不能隐藏业务时间读取次数。

### 4.2 应用层统一读取并向领域层传递

应用服务通过构造器注入 `Clock`，在一次用例入口读取时间：

```java
public void execute(...) {
    LocalDateTime now = utcNow();
    VideoLike like = repository.find(...);
    like.activate(now);
    repository.save(like);
}
```

应用层负责：

- 当前时间读取；
- 冷却期、超时和保留期阈值计算；
- 将同一个 `now` 传递给领域实体；
- 将同一个事件时间传给 Outbox、计数增量和审计字段。

领域层负责业务规则，但不负责发现系统当前时间。

### 4.3 领域实体改为显式接收时间

将以下形式：

```java
public static VideoLike create(...) {
    LocalDateTime now = LocalDateTime.now();
    ...
}

public void delete() {
    this.updatedAt = LocalDateTime.now();
}
```

改为：

```java
public static VideoLike create(..., LocalDateTime now) {
    ...
}

public void delete(LocalDateTime now) {
    this.updatedAt = now;
}
```

适用动作包括：

- `create`
- `revive`
- `activate`
- `deactivate`
- `delete`
- `rename`
- `addItem`
- `removeItem`
- 计数增加或减少
- 观看会话推进和关闭
- 分享记录创建
- 增量流水创建

参数约束：

- 时间参数不能为空；
- JavaDoc 说明时间必须由应用层提供且使用 UTC 语义；
- 领域方法不修改传入时间；
- 同一业务操作需要多个实体变更时，调用方传递同一个 `now`。

不通过重载保留无时间参数的旧方法。当前工程仍处于开发阶段，直接收敛为一套契约，避免旧方法继续偷偷读取系统时间。

### 4.4 观看链路

`WatchHeartbeatApplicationService` 是本次重点改造对象：

1. 入口只从 `Clock` 获取一次 `now`；
2. `lockOrCreateProgress` 接收该 `now`；
3. 会话超时判断、进度更新、心跳时间和事件凭据时间都使用同一时间快照；
4. 领域对象 `WatchProgress`、`WatchSession`、`WatchEventClaim` 的创建和推进方法接收显式时间；
5. 不允许继续出现类似以下两个独立时间源：

```java
lockOrCreateProgress(..., LocalDateTime.now());
LocalDateTime now = LocalDateTime.now();
```

起播和后续心跳的业务语义不变，只统一时间来源。

### 4.5 定时任务和汇总链路

以下任务统一通过 `Clock` 计算当前时间和截止时间：

- `WatchRetentionApplicationService`
- `CounterAggregationApplicationService`
- Outbox 清理任务（按 [Outbox 实施方案](interaction-outbox-retention-cleanup-implementation.md)）
- Outbox 租约认领、发布成功和失败退避

例如：

```java
Instant now = clock.instant();
LocalDateTime threshold = LocalDateTime.ofInstant(now.minus(retention), UTC);
```

同一轮汇总或清理使用一个 `now`，不能每处理一批记录重新读取当前时间，避免边界批次使用不同截止时刻。

### 4.6 持久化转换层

持久化层只负责对象映射，不负责创建业务时间。

需要调整的情况包括：

- `CounterDeltaRepositoryImpl` 在参数为空时调用 `LocalDateTime.now()`；
- `InteractionShareRecordPO` 在数据库时间为空时调用 `Instant.now()` 或 `LocalDateTime.now(ZoneOffset.UTC)`。

处理原则：

1. 业务创建路径必须始终显式提供时间；
2. 对数据库异常缺失时间，不再静默补当前时间；
3. 若历史数据读取确实允许空值，返回 `null` 或使用明确的反序列化默认策略，并记录该字段的约束；
4. 不把映射层的兜底时间伪装成业务发生时间。

### 4.7 Outbox 和跨服务事件

- 事件 `occurredAt` 使用业务用例入口取得的 `Instant now`，而非发布时刻；
- `published_at` 使用 Outbox 发布成功时由注入的 `Clock` 取得的时间；
- 租约截止时间由同一 `Clock` 计算；
- 事件发生时间和发布成功时间保持不同语义，不能合并；
- 推荐侧消费时间不由互动服务生成，也不写入互动服务的事件发生时间。

## 5. 分阶段实施步骤

### 第一步：盘点和建立禁止清单

- 全量搜索 `LocalDateTime.now`、`Instant.now`、`LocalDateTime.now(ZoneOffset.UTC)`；
- 按领域、应用、基础设施和测试分类；
- 标出合法的测试数据构造调用和必须保留的外部时间语义；
- 将互动生产代码中的裸系统时间调用收敛为零。

### 第二步：统一 Clock 注入边界

为需要当前时间的应用服务、调度服务和仓储适配器增加构造器注入：

- `WatchHeartbeatApplicationService`
- `WatchRetentionApplicationService`
- `CounterAggregationApplicationService`
- `VideoMetadataApplicationService`
- `LikeApplicationService`
- `StarApplicationService`
- `InteractionQueryApplicationService`
- `CounterDeltaRepositoryImpl`（若仍需生成处理时间）
- 其他盘点发现的组件

已有 `InteractionOutboxRepository` 和 `InteractionEventPublisher` 的 `Clock` 依赖保持，不重复创建系统时钟。

### 第三步：改造领域 API

按领域聚合逐组修改：

1. 点赞：`VideoLike`；
2. 收藏：`StarFolder`、`StarItem`；
3. 计数：`VideoCounter`、`CounterDelta`；
4. 分享：`InteractionShareRecord`；
5. 观看：`WatchProgress`、`WatchSession`、`WatchEventClaim`；
6. 快照及其他领域对象。

每组修改后立即更新调用方和单元测试，不保留无时间参数的兼容重载。

### 第四步：改造应用和调度流程

- 每个用例入口只读取一次 `now`；
- 将 `now` 传给所有领域变更和事件构建；
- 统一阈值计算的时间基准；
- 删除持久化层和实体内部的时间兜底；
- 补充关键步骤中文注释，说明统一时间快照对事务一致性和可测试性的原因。

### 第五步：修订测试

- 单元测试使用固定 `Clock`；
- 时间边界测试不使用真实等待；
- 增加同一事务多个字段时间相等的断言；
- 增加 UTC 与非 UTC 系统默认时区下的行为测试；
- 更新所有受影响的构造器和领域工厂调用。

### 第六步：同步文档与验收

- 更新 `docs/issues/interaction.md` 的 INT-12 状态和链接；
- 更新 `docs/modules/interaction.md` 的时间约定和源码索引；
- 在方案验收后再将 INT-12 标记为“已解决”；
- 未完成全量搜索和测试前，不得仅因部分服务已注入 `Clock` 就关闭问题。

## 6. 配置与兼容边界

本方案不新增环境变量，不改变 HTTP、RabbitMQ、JWT 或数据库表结构。

保留并统一使用现有：

```java
@Bean
Clock clock() {
    return Clock.systemUTC();
}
```

测试可以通过测试配置替换为固定时钟，但生产配置不得使用固定时间。

当前开发阶段不保留旧的无参数领域方法，不新增 `V1`/`V2` 类、路由或事件版本。时间来源变化属于内部实现和测试契约调整，不改变对外事件字段语义。

## 7. 测试与验收标准

### 7.1 静态检查

- 生产代码中互动模块不再存在裸 `LocalDateTime.now()`、`Instant.now()` 或 `now(ZoneOffset.UTC)`；
- 允许的例外必须是测试代码、明确的外部输入解析或 Clock 实现本身，并在验收记录中列出；
- 所有需要当前时间的应用服务都通过构造器注入 `Clock`。

### 7.2 领域单测

至少覆盖：

- 点赞创建、恢复、取消的 `createdAt/updatedAt` 使用传入时间；
- 收藏夹和收藏明细创建、重命名、删除使用同一时间；
- 计数快照和增量流水时间一致；
- 分享记录创建时间使用传入的 `Instant`；
- 观看会话创建、心跳推进、超时关闭和事件凭据时间一致；
- 传入固定时间时，不受测试机器系统时钟影响。

### 7.3 应用服务和调度测试

至少覆盖：

- 同一用例的多个更新字段使用同一个 `now`；
- 心跳不再两次读取系统时间；
- 冷却窗口、会话超时和观看保留期的边界使用固定时间准确判断；
- 计数汇总同一批次使用同一个截止时间；
- Outbox 的 occurred、published 和 lease 时间语义不混淆；
- 清理任务与时间源统一方案兼容。

### 7.4 集成验证

至少运行：

```bash
./mvnw -pl service/interaction-service -am test
```

若环境具备本地 MySQL，再显式运行互动模块的基础设施集成测试，并验证：

- `DATETIME(3)` 持久化按 UTC 读写；
- 不同 JVM 默认时区不会改变冷却期、超时和清理结果；
- 事务回滚不会留下由时间兜底代码生成的伪记录。

## 8. 风险与回退

### 风险

- 领域方法签名变化范围较大，容易遗漏调用方；
- `LocalDateTime` 与 `Instant` 混用时可能产生时区转换错误；
- 删除持久化层时间兜底后，历史脏数据或空字段可能暴露；
- 定时任务使用固定时间快照后，必须确认任务重试的时间语义符合预期。

### 控制措施

- 先全量搜索，再按聚合分组修改；
- 统一使用 UTC 转换方法；
- 对关键时间字段增加非空校验或明确空值策略；
- 通过固定 Clock 和非 UTC 默认时区测试；
- 实施后再次执行静态搜索，确认没有新增裸调用。

### 回退

本方案不引入数据库结构变化，代码回退可通过回滚本次代码变更恢复。回退时不得仅恢复某个实体的 `now()` 调用而保留应用层固定时间，否则会重新形成混合时间源；必须整体回退到一致版本。

## 9. 交付清单

- [ ] 互动生产代码裸系统时间调用清单归零或有明确豁免；
- [ ] 应用服务和调度服务统一注入 `Clock`；
- [ ] 领域对象所有时间相关方法改为显式时间参数；
- [ ] 持久化映射层不再静默生成业务当前时间；
- [ ] 测试替换为 `Clock.fixed()` 或可控时钟；
- [ ] UTC 和 `LocalDateTime` 持久化约定已记录；
- [ ] 单元测试、模块编译和必要集成测试通过；
- [ ] `docs/issues/interaction.md`、`docs/modules/interaction.md` 已同步；
- [ ] INT-12 在验收完成前保持“待处理”。

## 修订记录

- 2026-09-27：按方案 B 形成互动模块全时间源统一的正式实施方案，未修改代码。
