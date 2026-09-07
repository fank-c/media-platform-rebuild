# Auth Outbox 提交后快速投递修改方案

- 状态：**已获用户实施授权并完成代码实现；仍待独立验收和隔离依赖验证**。
- 编写日期：2026-09-06；实施完成日期：2026-09-06。
- 范围：仅 auth 注册事件投递触发、领取和恢复机制；未改变 API、事件 v1、表结构或 user 消费逻辑。
- 文档归属：依据[文档中心](../README.md)放入 reference；入口已补入文档中心，不新增 tasks。
- 前置约束：[协作规则](../../AGENTS.md)、[架构](../architecture.md)、[迁移计划](../migration-plan.md)、[契约规则](../contracts/README.md)。

## 1. 结论与业务边界

推荐保留“账户与待发事件在同一本地事务中提交”，增加“提交成功后，只向有限内存队列提交 eventId，工作线程取得执行槽位后按 ID 条件领取并投递”，同时保留**不依赖快速执行器的低频扫描**。Outbox 是持久化待发记录；快速任务只是可丢失的唤醒提示，不能成为唯一可靠性来源。

注册成功仍只代表账户与事件已提交，不代表 user 资料已生成。RabbitMQ 或 user 故障不能成为注册成功的前置条件。目标是减少正常注册等待下一轮扫描的时间，而不是同步初始化资料或承诺即时一致性。**当前暂无压测、数据库负载或端到端收益证明**；增加线程、按 ID 查询和低频恢复也有成本，必须在后续测量中比较。

不改变对外 API、错误码、JWT、事件 v1 字段/类型/路由、表结构或所有权；不新建索引，不修改 `common` 业务边界，不读取或触碰相邻 calles。本次不加入用户同步调用、生产重放、切流、历史单体迁移或 user 生命周期变更。新 ADR 仅列为实施工作，不能改写已接受 ADR 的历史结论。

## 2. 修改前的代码现状与缺口（历史基线，非当前行为）

以下均为本轮工作区静态证据，不等于运行正确；现有工作区有大量既有未提交变更，本方案不覆盖它们。

| 证据 | 当前行为 | 本次影响 |
| --- | --- | --- |
| [AuthService](../../service/auth-service/src/main/java/com/calles/platform/auth/application/AuthService.java) `register`，279–327 行 | `@Transactional` 内先插账户，再由工厂创建事件并 `outboxRepository.insert`；没有提交后快速通知 | 保留同事务，持有工厂生成的同一 eventId，增加仅提交成功触发的提示 |
| [AuthOutboxPublisher](../../service/auth-service/src/main/java/com/calles/platform/auth/infrastructure/outbox/AuthOutboxPublisher.java) `publishPending/publishOne` | 默认 fixedDelay 1s；一次领取一批后串行发送；finally 查询积压；ack 且无 returned 才标记成功 | 拆开调度、按 ID 分发、发送、统计；快速和扫描不得复制两套状态机 |
| [Repository](../../service/auth-service/src/main/java/com/calles/platform/auth/infrastructure/outbox/AuthOutboxRepository.java) `claim/claimOne` | `@Transactional` 内 `FOR UPDATE SKIP LOCKED` 锁候选，批量写租约、attempts+1，返回整批快照 | 原锁内调用不等于可安全直接按 ID 调用；禁止提前领取后排队 |
| [Mapper](../../service/auth-service/src/main/java/com/calles/platform/auth/infrastructure/outbox/AuthOutboxMapper.java) `markClaimed` | UPDATE 只有 event_id 条件；成功和失败回写已有 status+claim_token 条件 | 新按 ID 领取必须补完整资格谓词与更新行数判断；统一两条入口 |
| 同上 `findClaimableEventIds/loadBacklogSnapshot` | 到期 PENDING 或过期 PROCESSING；未限制 attempts；统计无 WHERE，聚合整个 auth_outbox | 崩溃后租约回收可绕过重试上限；不能每次快速任务都触发全表聚合 |
| [参数类](../../service/auth-service/src/main/java/com/calles/platform/auth/config/AuthOutboxProperties.java)、[YAML](../../service/auth-service/src/main/resources/application.yml) | enabled=true、batch=100、confirm=5s、lease=30s、maxAttempts=20；只检查 lease>confirm | 参数校验无法排除批内等待和其他阻塞；需改变领取时机而非只调大租约 |
| [ProfileBackfillService](../../service/auth-service/src/main/java/com/calles/platform/auth/application/ProfileBackfillService.java) `enqueue` | 补齐进度与 Outbox 同事务；唯一进度键避免重复入队 | 本次不增加快速通知，不让批量补齐抢注册执行器 |
| [ProfileBackfillJob](../../service/auth-service/src/main/java/com/calles/platform/auth/infrastructure/scheduling/ProfileBackfillJob.java) | 默认关闭、dry-run=true、100 条、10s fixedDelay；逐账号 enqueue，失败后继续其他账号 | 保持生成策略；生成与实际发布是两个阶段，发布受新扫描周期影响 |
| [AuthOperationalMetrics](../../service/auth-service/src/main/java/com/calles/platform/auth/infrastructure/observability/AuthOperationalMetrics.java) | 已有发布结果计数及 PENDING/FAILED/最老积压 Gauge，快照缓存在内存 | 保留名称及含义，新增独立低频刷新与快照新鲜度 |
| [PublisherTest](../../service/auth-service/src/test/java/com/calles/platform/auth/infrastructure/outbox/AuthOutboxPublisherTest.java) | mock RabbitTemplate，仅覆盖 ack/nack 等部分行为 | 不证明真实 confirm/return 顺序、网络故障和路由行为 |
| [RepositoryTest](../../service/auth-service/src/test/java/com/calles/platform/auth/infrastructure/outbox/AuthOutboxRepositoryTest.java)、[AuthServiceTest](../../service/auth-service/src/test/java/com/calles/platform/auth/application/AuthServiceTest.java) | mock Mapper/依赖验证编排，直接构造对象 | 不证明 MySQL 锁、真实提交/回滚或 Spring 代理事务 |

### 2.1 必须先解决批量租约问题

当前默认一次领取 100 条，租约一起开始；若每条 confirm 等待接近 5 秒，第 100 条可能仅前序等待就接近 495 秒，整批约 500 秒，远超 30 秒租约。这只是等待预算示例，不是实测，且尚未计入 send 和数据库等待。另一实例可能回收后面的记录，原实例仍继续发送。claim_token 只能阻止旧结果覆盖新领取者，不能撤回已发消息。

因此不能把方案简化成“增加快速线程并把扫描调到 10 秒”。第一实施阶段就必须消除**整批提前持有租约**，否则快速线程会增加与扫描的竞争。

## 3. 目标执行链与职责

```text
注册线程：账户 INSERT + Outbox INSERT + 登记提交后提示（同一本地事务）
  ├─ 回滚：无持久事件，无快速提示
  └─ 提交：afterCommit -> 非阻塞 execute(eventId) -> 注册按原契约返回
                          ├─ 拒绝/停机：提示丢弃，Outbox 保留
                          └─ 快速工作线程实际开始运行
                               -> 独立短事务条件领取并提交
                               -> 无数据库事务发送、等待 confirm/return
                               -> 独立短事务按 claim_token 回写
独立扫描线程：低频发现到期候选 ID -> 逐条同样领取、发送、回写
独立统计调度：低频全表聚合 -> 更新内存 Gauge，不参与发送成功判定
```

建议按职责做局部拆分，不搬迁全仓已有包：

| 单元（新名字为建议，不是现状） | 建议位置与责任 |
| --- | --- |
| 注册提交后通知窄接口 | `application/outbox`：仅接收 eventId；AuthService 不持有 RabbitTemplate/线程池，不把通知加进通用 Repository.insert |
| 通知适配器、有限执行器配置 | `infrastructure/outbox`、`config`：登记事务同步、捕获提交拒绝、管理执行器生命周期；队列任务仅携带 eventId，不捕获账户、请求、完整事件或事务资源 |
| 按 ID 分发器 | `infrastructure/outbox`：在工作线程调用仓储代理取得快照，再调用统一发送器；自身不以事务包裹全流程 |
| AuthOutboxRepository/Mapper | 原位置：统一资格、attempts、租约、令牌和条件回写；不负责消息发送 |
| AuthOutboxPublisher | 保留为统一单条发送能力，移出周期扫描和全表统计；两个入口共享同一逻辑 |
| 扫描 Job、积压指标 Job | `infrastructure/scheduling`：扫描有专用单线程执行进度；指标有独立调度资源，不与等待 confirm 共用默认单线程调度器 |

不新增框架依赖；使用现有 Spring 事务、调度与 Java 有界执行器。补充涉及类型、属性、构造器、私有方法和关键事务/失败步骤的中文注释，并同步对应职责测试。

## 4. 事务与执行器：注册不承担投递故障

### 4.1 提交后的事务边界

1. 在注册事务内先成功插入账户与 Outbox，保留唯一生成的 eventId，再登记事务同步回调。不得在提交前提交任务，也不得在快速路径重建事件 ID 或重新序列化业务账户。
2. 推荐 `TransactionSynchronization.afterCommit` 回调**只执行非阻塞 execute**，不查库、不领取、不发送、不等待 Future。同步登记前确认存在活动事务；若不满足，不同步补发，记录低敏感度故障并让持久记录由扫描处理；真实注册代理测试必须发现事务误接线，不能将此降级当正确性证明。
3. 真正的新工作线程不继承注册事务，调用**独立 Bean 代理上的 `@Transactional`（默认 REQUIRED）**即可开启新的领取事务。领取提交完成才发送；成功/失败回写也通过代理进入独立短事务。禁止同类自调用绕过代理，禁止将连接、事务上下文复制到工作线程。
4. `afterCommit` 虽已提交，原线程仍可能留有事务资源；若未来改成回调内直接做数据库事务操作，必须经过独立代理显式 `REQUIRES_NEW`。本推荐没有这种操作，**不无条件给注册 insert 或全部仓储方法加 REQUIRES_NEW**，否则可能破坏原子落库。
5. 可定位的官方依据为 Spring Framework 6.1.12 `TransactionSynchronization.afterCommit` JavaDoc（本轮读取确认）；其说明残余资源和回调异常向调用者传播。审查时应按仓库最终解析版本复核：

```text
https://docs.spring.io/spring-framework/docs/6.1.12/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit()
```

### 4.2 有界、拒绝与关闭

- 推荐固定 2 个快速线程，队列容量 256，AbortPolicy 或等价的显式拒绝策略；**禁止 CallerRunsPolicy、同步执行器、无界队列、阻塞等待队列空位**。队列只保存 eventId，只有工作线程真正开始执行才领取；排队期间 attempts 不增加、租约不启动。
- execute 边界捕获 `RejectedExecutionException`/Spring 对应拒绝异常及提交阶段 RuntimeException，不透传给注册；指标或日志异常也不能让提交后的通知抛回调用者。记录固定结果标签，不写载荷/密码/Token。不要用捕获 JVM 致命 Error 作为可用性保证。
- 工作线程异常在任务边界收敛，保证后续任务仍运行；数据库回写失败时保留 PROCESSING 等租约恢复，不紧循环发送，也不尝试在注册线程补偿。
- 总开关与快速开关在启动期决定 Bean/执行器注册；关闭时注入空通知实现，避免缺 Bean 使注册无法启动。不宣称 Nacos 热刷新能即时启停线程池；采用受控重启，开关含义是“不再开始新投递”，无法撤回在途发送。
- 停机先停止接收新提示/扫描轮次；允许在途任务有限等待（建议 10s），超时结束。未执行的 ID 可丢弃，已领取的记录等待租约回收；中断应恢复线程中断标志并退出，不无限延长停机。不能承诺阻塞客户端一定可被中断，须故障验证。

## 5. 统一条件领取与有界恢复

### 5.1 资格和原子操作

定义同一资格（时刻使用一致的 UTC 基准，实例时钟需同步）：

```sql
(status = 'PENDING' AND next_attempt_at <= :now)
OR (status = 'PROCESSING' AND lease_until < :now)
```

新按 ID 更新在短事务内使用以下完整条件，不复用当前只有 ID 的无条件 markClaimed：

```sql
UPDATE auth_outbox
SET status = 'PROCESSING', lease_owner = :owner, lease_until = :leaseUntil,
    claim_token = :newUniqueToken, attempts = attempts + 1
WHERE event_id = :eventId
  AND attempts < :maxAttempts
  AND ((status = 'PENDING' AND next_attempt_at <= :now)
       OR (status = 'PROCESSING' AND lease_until < :now));
```

每次成功领取生成新 claim_token。仅更新行数为 1 时，才在同一事务内读取并核对 token 的发送快照，提交后返回；0 行是正常竞争失败/未到期/终态/缺失，不发消息、不计失败、不增加 attempts。快速任务重复、快速与扫描、多实例扫描都走这个条件更新；MySQL 并发保证必须实测。

发送成功、失败回写保留 `event_id + PROCESSING + claim_token` 条件，并检查更新行数；旧 token 的 0 行结果仅记录 lost-claim，不覆盖状态，也不再次标记当前新持有者失败。租约到期本身不撤销 Broker 发送，最终仍是至少一次。

### 5.2 扫描不提前租赁、不被快路径饿死

推荐把原 `claim(batch)` 改为“只读发现最多 batchSize 个到期候选 ID，按 occurred_at/event_id 排序，然后在**扫描专用线程**逐条调用同一个按 ID 领取及发送流程”。候选读取不提前写租约，不保存待发送消息快照，不在列表发送全程持锁；其并发安全来自后续条件 UPDATE，而非快照仍新鲜的假设。原 `FOR UPDATE SKIP LOCKED` 批量领取方法不再作为发送入口。

扫描每次最多处理一个候选批次，完成后 fixedDelay 等待；某条数据库/发送失败须隔离，不取消整轮剩余候选。多实例可能读到相同候选，输掉条件更新就跳过，下轮继续；检查实际查询计划与竞争次数，不宣称无需索引调整就有确定吞吐。不将扫描任务投递到注册快速队列：即使快路径持续饱和，扫描仍有 1 个独立发送槽位；统计和补齐 Job 不应占住该专用调度资源。数据库/Broker 本身阻塞仍可能拖慢所有通道，这不是资源隔离能完全解决的。

### 5.3 attempts 上限与 FAILED

沿用“**领取次数**”而不是“Broker 实际收到次数”语义，默认 20，不改变外部契约。只在成功条件领取时 +1；提交拒绝、竞争失败不计数。崩溃可能消耗一次领取而未发送，必须明确接受这一代价。

当前仅 `markFailed` 根据 attempts 判断上限，进程反复崩溃会绕过该出口。建议扫描增加一个**有界终态收敛步骤**：普通发送候选查询必须包含 `attempts < maxAttempts`，避免耗尽记录占用普通候选批次。另行发现满足上述到期资格且 `attempts >= maxAttempts` 的 ID，以同样资格和次数条件原子更新为 FAILED，清理租约、写固定低敏感度错误分类（如 `ATTEMPTS_EXHAUSTED`），不再发送。不能处理尚未过期的 PROCESSING，也不能将正常在途第 20 次发送提前作废。该步骤每轮最多 batchSize 条，与普通候选处理分别有界，避免耗尽记录挤掉可投递记录。快速按 ID 遇到同类记录可调用同一终态操作，不能绕过上限。

普通失败保留当前指数退避、正向抖动和封顶逻辑：基础值 `min(300, 2^min(8, max(0, attempts-1)))` 秒，抖动为 `0..max(1, floor(base/5))` 秒，最终最多 300 秒；不顺手重写为另一条曲线。未耗尽回 PENDING 并设置 next_attempt_at；耗尽进 FAILED。FAILED 不自动扫描重发，受控重放需另行授权，保留 eventId，先判断消息可能已到 Broker。部署时降低 maxAttempts 会让既有高 attempts 记录更早进入 FAILED，禁止作为无影响调参。

## 6. 发送语义与租约限制

保留 [v1 契约](../contracts/events/auth.account.created.v1.md)的 exchange `media.platform.events`、routing key `auth.account.created.v1`、messageId=eventId，直接发送数据库原始 payload；不变更信封、traceId 或消费者宽松兼容规则。Rabbit 配置继续使用 correlated confirm、publisher-returns=true、mandatory=true；[拓扑配置](../../service/auth-service/src/main/java/com/calles/platform/auth/config/AuthMessagingConfiguration.java)不承担 user 队列所有权。

只有 confirm ack **且无 return** 才可条件更新 PUBLISHED。nack、return（包括 ack+return）、confirm 超时、send 异常均进入既有失败/退避流程；只有数据库成功回写后计发布成功。confirm 成功但数据库失败时，租约回收后可能重复发；超时也不表示 Broker 没收到。

**confirmTimeout 只约束 `correlation.getFuture().get(...)`，不包含 `rabbitTemplate.send`、连接/channel 获取、数据库连接/锁等待、进程暂停和回写耗时。lease > confirmTimeout 不足以消除重复。** 建议保留 30s 租约作为初始值，移除批量排队消耗后，后续用真实环境测量“领取提交到回写结束”的分位耗时，并核实 JDBC/连接池/AMQP 客户端的阻塞上界、单独校验连接和查询超时配置。若无法给出足够余量，不得宣称租约安全或进入扩大启用阶段；调大租约也会延后崩溃恢复。本次不加入自动续租或复杂异步 confirm 状态机。

[用户事务处理器](../../service/user-service/src/main/java/com/calles/platform/user/application/event/AccountCreatedEventProcessor.java)已有 `(consumer_name,event_id)` 幂等登记与初始化同事务的代码；至少一次必须继续依赖它。幂等不能被快路径替代，也不能把 publisher ack 等同于 user 已提交资料。后续验收须验证重复、并发、停用/墓碑保护及未知字段兼容，不改消费者业务实现。

## 7. 开关、建议参数与配置同步

以下是**可调整建议**，不是已生效配置；先保守部署，再逐步降低扫描频率。

| 配置键（均在 auth.outbox 下） | 环境变量 | 建议值与理由 |
| --- | --- | --- |
| enabled（已有） | AUTH_OUTBOX_ENABLED | true；总发送开关，false 同时关闭扫描和快速发送，注册/补齐仍写事件 |
| fast-dispatch-enabled（新增） | AUTH_OUTBOX_FAST_DISPATCH_ENABLED | false；默认可回退基线，独立验收后小范围设 true |
| fast-dispatch-threads（新增） | AUTH_OUTBOX_FAST_DISPATCH_THREADS | 2；限制 Broker 故障时阻塞线程，不作为吞吐承诺 |
| fast-dispatch-queue-capacity（新增） | AUTH_OUTBOX_FAST_DISPATCH_QUEUE_CAPACITY | 256；有限吸收短突发，超量直接退回扫描，不据此假设排队时延 |
| batch-size（已有） | AUTH_OUTBOX_BATCH_SIZE | 100；改为单轮候选上限而非同时持有租约数，需同步注释和配置语义说明 |
| poll-interval（已有） | AUTH_OUTBOX_POLL_INTERVAL | 首次发布仍 1s；快路径验证后推荐 10s（空闲扫描频率理论降低，不是实测收益） |
| max-attempts（已有） | AUTH_OUTBOX_MAX_ATTEMPTS | 20；保留领取计数，补齐崩溃后的上限出口 |
| confirm-timeout（已有） | AUTH_OUTBOX_CONFIRM_TIMEOUT | 5s；保留行为，不代表整个发送上界 |
| lease（已有） | AUTH_OUTBOX_LEASE | 30s；初始运维折中，必须另测 send/DB/进程暂停余量 |
| backlog-refresh-interval（新增） | AUTH_OUTBOX_BACKLOG_REFRESH_INTERVAL | 60s；把全表统计从每轮发送抽离，接受快照滞后 |
| shutdown-await（新增） | AUTH_OUTBOX_SHUTDOWN_AWAIT | 10s；限制正常停机等待，不保证不可中断调用立即结束 |

校验：线程/队列/批次/次数必须为正且有合理上界（线程建议最多 16、队列最多 10000、批次最多 1000，超出需重新评估）；时长必须为正，lease>confirm 仅保留最低约束并说明局限。所有默认值在 Java、YAML、环境示例一致。两个开关组合：总=false 时 fast=true 也不发送；总=true/fast=false 仅扫描；两者 true 才双通道。统计不受总发送开关影响，停发时仍能发现积压；数据库统计失败保留旧值并标记过期。

### 7.1 扫描降频的实际代价

空闲且扫描未受阻时，快速提示丢失、补齐事件发布、到期重试，都可能多等待接近一个 10s 间隔；这是直观预算，**不是 10 秒恢复 SLA**。fixedDelay 从上轮完成才计时，100 条若每条仅 confirm 就等待 5s，一轮仍可接近 500s，再加 10s 和其他阻塞。单条即时领取修复了批量租约，但没有消除串行扫描吞吐限制。补齐还叠加自身 10s 的生成周期；失败恢复是退避到期再等扫描；崩溃恢复是租约过期再等扫描。

因此建议注册快路径启用后才尝试 10s；批量补齐窗口或故障恢复目标较严时，暂保留/恢复 1s，但不要声称缩短间隔能消除积压处理时间。若扫描持续落后，应回到方案评估受控并行和容量，而非让补齐争用注册队列或静默增大线程池。

### 7.2 后续实施必须同步的文件

- [AuthOutboxProperties](../../service/auth-service/src/main/java/com/calles/platform/auth/config/AuthOutboxProperties.java)、[AuthConfiguration](../../service/auth-service/src/main/java/com/calles/platform/auth/config/AuthConfiguration.java)、服务 YAML 与新增执行器配置：绑定、校验、条件注册、重启语义、注释及属性测试。
- [.env.example](../../.env.example)：补新增键和中文说明，不提供任何真实凭证。
- [Compose](../../docker-compose.yml)：已核对只编排基础设施，不含 auth-service；检查现有变量关联并记录“无需新增 auth env”，不凭空加入服务或无消费者的环境项。
- [Nacos 指南](../nacos-config-guide.md)：保留 `optional:nacos:application.yml` 的实际 Data ID；说明实际配置源、开关需重启、不要污染共享配置，不假设全量热刷新。
- [项目首页](../../README.md)、[认证设计](authentication.md)、[消息设计](messaging.md)、[测试指南](../guides/testing.md)、[监控](../operations/monitoring.md)、[部署](../operations/deployment.md)、[迁移计划](../migration-plan.md)：同步行为、参数、验收边界和回退，去除与新机制冲突的描述；契约只补可靠性说明，不改字段和语义。
- 实施前按 [ADR 模板](../adr/0000-template.md)新增后续编号 ADR，说明这是对[已接受 Outbox 决策](../adr/0002-auth-user-profile-outbox.md)投递调度的细化，记录备选方案、风险、验证和回退；更新 ADR 索引，不改写 0002/0003/0004 的历史结论。本轮不新增 ADR，不修改上述任何文件。

## 8. 统计与可观测性

从 publishPending 的 finally 移除积压聚合，独立 60s 任务刷新现有 Gauge，快速成功/失败不得触发全表扫描，指标采集线程只读缓存。当前查询包括 PUBLISHED 历史记录的全表聚合，降频只减少次数，**不消除随数据增长的单次代价**；后续以隔离数据测量耗时和查询计划，若仍不可接受另行讨论优化，不能夹带删历史数据或加索引。

建议新增固定低基数指标：快速提示 accepted/rejected/disabled、领取 claimed/skipped/lost、队列深度与活动线程、扫描轮次/耗时/末次完成时间、单条派发耗时、终态耗尽计数、积压刷新失败与末次成功时间。保留已有 publish_total 的结果意义，不把“提示 accepted”记作已发布。可在内部快照增加 occurredAt/traceId 以计算延迟和恢复安全追踪，不改持久结构或外部事件；日志恢复 MDC 后 finally 清理，队列仍只携带 ID，禁止 eventId/accountId/traceId 作指标标签。

建议告警起点（待实测调整）：快速拒绝持续 5 分钟、FAILED>0、最老未发布超过 120s、快照超过 2 个刷新周期未成功；扫描末次进度阈值结合单轮耗时而非简单两倍 poll。通过受控 MeterRegistry 出口/测试注册表验证，不新增公网管理端点。启动 readiness 保持现有 db/redis 依赖，不把 RabbitMQ 故障改成注册不可用条件。

## 9. 后续实施与验证门槛

### 9.1 已执行实施顺序

1. 补独立 ADR；实现统一按 ID 条件领取、终态收敛、令牌条件回写，把扫描改成逐条即时领取；先保留 1s 和快速关闭，验证恢复能力。
2. 加注册提交后窄通知和有限执行器，拒绝安全降级；不改补齐 enqueue。拆出扫描、统计独立调度，并保留共用发送器。
3. 同步配置/文档/中文注释与测试；先通过下表真实依赖验证，再考虑快速开启和扫描降频。偏差若需要表结构、依赖或对外语义变更，返回方案审查，不自行扩大范围。

### 9.2 故障与兼容矩阵

| 场景 | 必须观察的结果 | 证据层次 |
| --- | --- | --- |
| 注册成功、账户插入失败、Outbox 插入失败、提交失败/回滚 | 账户与事件同成同败；仅成功提交有快速提示；API 响应契约不变 | 真实 Spring 事务代理 + 隔离 MySQL；独立连接核对提交可见性，不能只测 mock 调用顺序 |
| afterCommit 时资源仍绑定 | callback 仅 execute；工作线程不同，领取/回写真实提交；注册 insert 未被独立提交 | Spring 上下文 + 真数据库，通过代理调用，不用手工 new 或同类自调用 |
| 执行器满、关闭、任务异常、日志/指标失败 | 注册仍成功，数据库保留可扫描记录，无 CallerRuns/同步执行 | 小容量执行器+闩锁控制，不用睡眠推测；捕获执行线程与 SQL 时机 |
| ID 排队较久、重复提示 | 开始执行前仍无 lease/attempts 增长；重复 ID 仅有效领取者发送 | 真数据库 + 受控执行器 |
| 快速/扫描、两快速、两实例并发同 ID | 同一有效租约只有一个成功更新；竞争者 0 行不发送；其他 ID 可进展 | 隔离 MySQL 多连接 + Spring 代理 + 并发屏障；现有 Mockito Mapper 测试不能证明锁 |
| 未到期 PENDING、有效 PROCESSING、PUBLISHED、FAILED、缺失 ID | 都不领取；过期 PROCESSING 可换 token 领取；旧 token 回写 0 行 | 真 SQL；边界 `next_attempt_at=now` 与 `lease_until=now` 分别测试 |
| 第 20 次领取后崩溃、反复崩溃回收、既有 attempts>=20 | 不产生第 21 次自动领取；过期后有界转 FAILED；有效租约不提前终结 | 真数据库 + 故障注入/进程终止，核对 attempts 和状态 |
| Broker ack、nack、不可路由 ack+return、超时、send 异常 | 仅 ack 且无 return 回写 PUBLISHED，其余退避/终态；保持原 eventId | 真实隔离 RabbitMQ；通过存在 exchange 但无绑定制造 return，nack 需可靠故障注入，无法复现时明确缺证，不用 mock 宣布通过 |
| send/DB 慢、确认后回写失败、租约过期旧持有者返回 | 不覆盖新 token；可能重复发送但不丢记录；恢复可观测 | 真依赖 + 可控阻塞；区分 get 超时与整体发送耗时 |
| 100 条慢发送 | 未轮到的记录不提前 PROCESSING；已处理条正常回写；后续租约从实际领取起算 | 真数据库/受控 Broker；复现原 100×5s 与 30s 风险，测试可缩放时间但不能改变因果 |
| 快速队列长期饱和、指标查询阻塞 | 扫描仍独立推进，快速拒绝回退；指标故障不更改发送结果 | 独立线程和依赖故障测试，记录扫描进度与资源占用 |
| 补齐 enabled/dry-run 组合、并发 enqueue | 保留补齐进度+Outbox 原子性与唯一资格；不触发快速执行器；低频扫描最终发布 | 原补齐测试扩展 + 真事务集成 |
| 失败退避、抖动、终态、单条回写异常 | next_attempt_at 在既有边界内；FAILED 不自动发；异常不取消同轮其他条 | 固定 Clock/范围断言 + SQL 校验 |
| 两开关四组合、总开关关闭、重启/停机 | 关闭不注册发送资源，写入不停止；统计仍可见；未执行提示可恢复，无无界停机 | Spring 上下文与启动探针，验证 Bean 图和线程生命周期 |
| 重复事件、未知字段、旧 v1、资料停用/删除 | user 幂等事务保留，不覆盖既有/受保护资料；消息字节/字段语义不变 | user 现有测试回归 + 真 RabbitMQ 消费集成，稳定 eventId |
| 统计降频及关闭发送后的刷新 | 高频注册不增加聚合次数；60s 独立刷新；失败保留旧快照且新鲜度告警 | SQL 调用计数/隔离数据库观测；不把静态检查当数据库性能证明 |

### 9.3 执行环境与证明限制

本次实现后已在无冒号、无符号链接的临时工作区副本运行 auth-service 单元测试；不触碰 calles。真实 MySQL/RabbitMQ 验证仍必须使用隔离环境和测试临时凭据，不连接生产、共享或未知环境。

建议在临时副本执行 `./mvnw -pl service/auth-service -am test`；若按类选测，加 `-Dsurefire.failIfNoSpecifiedTests=false` 处理上游模块无指定测试，并核对 auth 实际运行的测试数不是零。若集成测试采用独立 profile/Failsafe，必须在实施时给出实际命令及报告，不能认为 test 自动运行了未接入的 IT。需要回归消费者时再执行对应 user 模块测试。新增测试依赖须说明用途和替代，不未经说明引入新基础设施。

配置变更后校验 YAML、`docker compose config -q`、打包受影响服务并做启动和注册 HTTP 探针；Docker 不可用先记环境阻塞，不归罪于配置。最终报告分别列单测、真事务/SQL 并发、真 RabbitMQ、启动探针、未测故障和性能证据；任何未测关键项不得用历史 Mockito 测试替代。

## 10. 分阶段启用、比较与回退

| 阶段 | 配置与操作 | 前进条件 / 回退 |
| --- | --- | --- |
| 0 独立审查 | 本文待审，不部署 | 审查领取谓词、事务边界、attempts、恢复时延和测试充分性；修订后才授权实施 |
| 1 扫描基线 | 新实现 total=true、fast=false、poll=1s、统计=60s | 真依赖矩阵与恢复通过，记录新扫描基线；不要先降频隐藏恢复问题 |
| 2 快速小范围启用 | 经环境授权，选定实例 fast=true，poll 仍 1s | 观察拒绝率、队列/扫描进度、DB负载、重复量、注册错误/延迟；异常先 fast=false 受控重启 |
| 3 降频评估 | 通过后再 poll=10s，保留补齐关闭/dry-run 默认 | 验证提示丢失、补齐、重试、崩溃恢复时延可接受；不满足就恢复 1s，不修改事件或丢弃记录 |
| 4 扩大启用 | 仅有真实测量和独立验收证据后 | 不将“测试通过”写成“压测收益已证实”或“用户领域已迁移” |

比较方案至少包含低负载、注册突发、持续负载、Broker 故障及恢复、补齐开启场景。记录注册响应 p50/p95/p99、提交到发布确认/用户资料可读的延迟、扫描/按 ID/统计 SQL 次数和耗时、CPU/线程/连接数、拒绝量、积压年龄、重复与 FAILED。报告持续时间、样本数、数据规模、实例数和依赖配置；性能验收阈值需基线测量后由用户确认，不能凭推荐参数预写收益百分比。

首选逻辑回退：关 fast、恢复 poll=1s 并受控重启，保留修复后的单条领取扫描。若 Broker 故障需要暂停全部发送，关总开关但保留写入/指标；恢复前核对积压容量和旧租约，不自动重放 FAILED。开关关闭不阻止注册或补齐继续积累事件，需要告警和容量观察。

二进制回滚仅在必要时使用：新旧记录/状态和 v1 兼容，无 DDL 回滚；但回旧发布器会恢复批量提前租赁和崩溃绕上限风险，不能称等价安全回退。确需回旧版本时建议先总关闭，等待在途结束/旧租约释放，再以 batch-size=1、poll=1s 小范围恢复并观察；attempts 耗尽记录需核对，不能删除、重置或生产重放。该方式可能显著降低恢复吞吐，须记录风险并另行获得目标环境操作授权。

## 11. 交给独立审查者的重点决策

1. **恢复时延**：推荐快路径验收后扫描 10s，补齐仍不走快路径；是否接受其恢复/补齐延迟？若有更严要求，保留 1s，吞吐问题另评估，不假设 10s SLA。
2. **资源与启用**：推荐 2 快速线程、256 ID 队列、1 独立扫描槽位、60s 统计、默认快速关闭；是否接受这一保守起点？精确容量与性能目标待基线测量，不应阻塞对正确性方案的独立审查。
3. **次数耗尽语义**：推荐维持领取计数，并让过期且已耗尽记录进入 FAILED；崩溃前未真正发送也可能耗尽，需要人工受控处理。审查需明确接受该内部可靠性取舍，不改变 API/JWT/v1 或新建自动重放语义。

用户已于 2026-09-06 明确授权实施。下一步仍应由未参与实施者对照本文进行只读独立验收；快速通道默认关闭，任何隔离环境启用、扫描降频、生产重放或切流均需按本文第 10 节另行取得环境操作授权。
