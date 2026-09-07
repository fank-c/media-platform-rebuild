# 0003 通用事件信封与本地业务载荷

- 状态：已替代（仅 JSON 树解析与 Input 投影实现细节）
- 日期：2026-09-06
- 决策者：项目维护者、实施代理
- 关联任务/契约：`docs/contracts/events/auth.account.created.v1.md`、`docs/adr/0002-auth-user-profile-outbox.md`

> 替代说明（2026-09-06）：ADR 0004 保留本 ADR 的通用信封、本地载荷和协议边界结论，
> 仅以局部对象绑定后直接传递 `EventEnvelope<本地Payload>` 替代本 ADR 所述的 `JsonNode` 解析和
> `AccountCreatedEventInput` 投影实现；其余历史背景和决策文本保留以说明当时选择。

## 背景

`auth.account.created.v1` 已通过 Outbox 可靠发布，但认证端以 `Map` 拼装 JSON，用户端消费者为读取
traceId 解析一次消息、应用处理器再解析一次。应用用例因此依赖 `ObjectMapper`、`JsonNode` 与 RabbitMQ
原始字节，协议适配和业务事务边界混在一起。又不能为一个事件建立 `auth-contracts` 模块或把认证领域 DTO
放入 `common-core`，否则会形成共享业务模型并扩大后续演进耦合。

## 决策

在 `common-core` 提供不依赖框架的 `EventEnvelope<T>`，只承载通用事件元数据和泛型载荷容器。
`auth-service` 本地维护 `AccountCreatedPayloadV1`，以它构造 `EventEnvelope` 后序列化写入既有 Outbox。
`user-service` 本地维护最小 `AccountCreatedEventInput`；RabbitMQ 入站 Decoder 解析原始 JSON 一次、完成
v1 兼容校验和安全 traceId 提取，再调用不依赖 JSON/MQ 的应用处理器。

为保持既有 v1 行为，Decoder 可以在 interfaces 边界使用有限 `JsonNode` 读取：必需字段必须是真实文本，
`version` 保留现有 `asInt(-1)` 的接受范围，未知字段继续忽略，`producer`、时间字段和非法 traceId
不新增拒收条件。非法协议仍拒绝且不立即重新入队；数据库和资料初始化错误仍由监听容器有限重试。

## 备选方案与取舍

- 建立 `auth-contracts` 并共享 `AccountCreatedPayloadV1`：可减少重复字段名，但会让 user 直接依赖 auth 的
  领域表达，Payload 演进时扩大跨服务发布和兼容成本。
- 直接反序列化为 Java POJO：代码更短，但默认标量转换会改变原先“必需字段必须为 JSON 字符串”的拒收语义。
- 继续由应用处理器解析原始字节：不引入类型，但保留重复解析、框架依赖和协议逻辑泄漏。

## 影响与风险

消息字段、路由键、Outbox 状态机、至少一次投递、消费幂等键、重试配置和资料初始化规则均不变。
新增的通用信封不包含业务校验；具体契约仍必须在 `docs/contracts/events/` 维护。v1 的宽松 version 行为
被回归测试固定，未来若要收紧类型校验必须发布新版本，而不是在 v1 内静默改变。MDC 恢复改为保留监听
线程进入消费前的 traceId，降低线程复用时日志串链风险。

## 验证与回退

单元测试验证认证端序列化结构和最小披露、Decoder 的 v1 兼容边界、处理器的类型输入幂等编排，以及
消费者的拒绝/重试分类、指标时机与 MDC 恢复。隔离 MySQL 和 RabbitMQ 环境仍须验证注册、真实投递、
重复消费、死信和失败恢复；单元测试不能替代这些证据。

回退仅撤回本次应用代码和服务制品，保留 `auth_outbox`、`user_consumed_event`、队列与死信数据，不删除
记录、不重置投递状态，也不重放生产消息。既有 v1 JSON 仍可由回退后的消费者处理。
