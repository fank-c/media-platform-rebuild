# RabbitMQ 领域事件设计

## 状态与边界

RabbitMQ 已列入本地基础设施和架构基线，但当前没有可据以宣称审核、互动、推荐事件链路已完成的
业务契约与端到端证据。本文是**目标规范**，不是已部署的交换机、队列和消费者清单。
不再维护原指南中的示例消费者、数据库表和整段配置副本。

非即时的审核、推荐更新优先异步处理；即时请求是否需要同步调用，须在相应领域设计中论证。
网关不承载领域编排，RabbitMQ 不用作隐藏的 RPC。

## 事件契约

目标主题交换机为 `media.platform.events`，路由键为 `<domain>.<event>.<version>`，
例如 `content.published.v1`；例子不表示已经发布。

通用 `EventEnvelope<T>` 提供 `eventId`、`eventType`、`version`、`occurredAt`、`producer`、
`aggregateId`、`traceId`、`payload` 的无业务含义容器。它不依赖 Spring、RabbitMQ、JSON 或 ORM，
也不替具体领域 Payload 做校验。生产方以本地 Payload 构造信封；消费者在入站适配层完成协议校验后，
可直接将 `EventEnvelope<本地Payload>` 交给应用处理器。具体字段类型、必填性、时间格式和敏感字段仍以每个正式事件契约为准；
不传完整领域实体或密码、Token 等敏感值。

新增事件在 [契约目录](../contracts/README.md) 登记生产方、消费方、版本、路由键、幂等键、
失败策略和兼容规则；跨服务事件必须配套新 ADR。尚无正式消费者时不要伪造上线日期。

## 一致性、失败与重放

1. 同一业务事务写业务事实与 Outbox（本地待投递记录），或采用经审查的等价可靠投递方案。
2. 发布在本地事务之外完成，确认失败不得丢弃待投递记录；发布重试有次数上限和退避。
3. 消费者按 `eventId` 幂等处理；重复投递不能造成重复业务副作用。
4. 明确消费成功后确认的时机，并测试持久化前后故障和重复消息。
5. 超过重试上限进入死信和可观测失败出口；重放必须有权限、范围和审计。

### Auth 账号创建事件的访问层分工

认证服务的 `auth.account.created.v1` 已采用本地 Outbox。`AuthOutboxMapper` 负责
`auth_outbox` 的 SQL 和结果映射，`AuthOutboxRepository` 保留租约领取、领取标记、状态流转、
重试退避和短事务编排；`ProfileBackfillMapper` 负责补齐候选与唯一进度键，
`ProfileBackfillService` 在同一事务内协调补齐进度和 Outbox 写入。

发布 RabbitMQ、定时调度与 Micrometer 指标分别位于认证服务的 `infrastructure/outbox`、
`infrastructure/scheduling` 和 `infrastructure/observability`。`AuthOutboxProperties` 与
`ProfileBackfillProperties` 集中绑定运行参数，并在启动期校验批量、轮询、确认等待和租约关系；
配置键及其默认值保持不变。

用户服务的 RabbitMQ 入站适配器保留在 `interfaces/messaging`。`AccountCreatedEventDecoder` 对原始 JSON
只绑定一次，保留 v1 的必需文本字段与 version 兼容边界，并直接生成
`EventEnvelope<AccountCreatedPayloadV1>`；
`AccountCreatedConsumer` 负责协议接收、traceId 处理与失败出口；`AccountCreatedEventProcessor` 保留在
application，负责幂等登记和资料初始化用例。Micrometer 指标位于 `infrastructure/observability`；`UserMessagingProperties` 和
`UserProfileProperties` 集中绑定消费并发、有限重试和头像展示前缀，并在启动期校验。

这只是认证服务内部访问层的分工，不改变事件信封、路由键、至少一次投递语义或表所有权。若需回退，
只可回退应用访问层实现，并保留 `auth_outbox`、补齐进度、尝试次数和未发布事件；不得清空记录或重放消息。
其他领域的 Outbox 表、投递任务、重试队列和死信配置仍需按领域实现，不能仅凭本文认为已具备。
生产消息发布或重放必须另行确认，不提供批量清空或无条件重放指令。

## 验收和兼容

至少覆盖成功、重复、乱序或旧版本输入（按业务要求）、未知字段、发布故障、消费中断、重试耗尽和重放。
记录成功量、失败量、延迟与积压；消费者迁移完成前不得停止旧事件版本。

回退必须明确停止哪些生产者/消费者、保留哪些 Outbox 记录，以及旧系统如何接管；
不能通过删队列或丢弃未处理消息回退。领域迁移门槛见 [迁移计划](../migration-plan.md)。

## Auth Outbox 的提交后快速提示

认证注册的账户与 `auth_outbox` 仍在同一本地事务写入。提交成功后，应用层只通知一个携带 `eventId` 的
有界内存执行器；它不是可靠消息队列，也不会让 RabbitMQ、用户资料初始化或快速执行器拒绝改变注册响应。
提示丢失、队列饱和、进程重启和补齐事件均由独立扫描恢复。

扫描只读发现候选 ID，随后逐条使用同一条带 `status`、到期时间、`attempts` 和 `eventId` 条件的 UPDATE
领取。快速任务与扫描、多实例之间竞争失败时不发送也不记作业务失败；成功领取才创建新 `claim_token` 并
递增次数。发送器继续以数据库原始 v1 payload、固定 exchange/routing key 和 `messageId=eventId` 发送，
仅在 Confirm ack 且无 return 后回写 `PUBLISHED`。到期且次数耗尽的记录会有界收敛为 `FAILED`，不自动重放。

`AUTH_OUTBOX_ENABLED=false` 暂停快速和扫描发送，但注册/补齐仍持续写入 Outbox，积压统计仍会刷新；
`AUTH_OUTBOX_FAST_DISPATCH_ENABLED=false` 时只保留扫描。默认快速关闭、扫描 1 秒，实际启用和降低扫描
频率前必须完成隔离 MySQL/RabbitMQ 验证。详见[ADR 0005](../adr/0005-auth-outbox-post-commit-fast-dispatch.md)。
