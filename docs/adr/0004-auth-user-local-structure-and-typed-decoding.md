# 0004 Auth/User 本地目录收敛与类型化消息绑定

- 状态：已接受
- 日期：2026-09-06
- 决策者：项目维护者、实施代理
- 关联任务/契约：`docs/reference/auth-user-structure-and-event-refactor.md`、`docs/contracts/events/auth.account.created.v1.md`、`docs/adr/0003-typed-event-envelope-and-local-payloads.md`

## 背景

认证服务原本将密码、JWT、Redis 会话、Outbox 记录、Mapper、发布器和事件工厂分散在多层目录；用户服务
则在消息监听器和事务处理器之间额外传递 `DecodedAccountCreatedEvent`、`AccountCreatedEventInput` 两个仅做字段
投影的包装。这不改变行为，但增加阅读跳转，并使已采用的通用 `EventEnvelope<T>` 未能直接表达消费者输入。

ADR 0003 已接受“树解析后投影 Input”的实现选择。实际收敛时确认：在不改变 v1 字段、宽松 version 规则、
Outbox 状态机、MDC、重试和幂等事务的前提下，可由局部 Jackson Reader 直接生成带本地 Payload 的信封。

## 决策

- auth-service 保留 `interfaces`、`application`、`domain`、`infrastructure` 四个职责边界：密码/JWT/Redis 会话归入
  `infrastructure/security`，账户创建 JSON 类型与工厂归入 `infrastructure/messaging`，Outbox 的记录、状态、SQL、
  仓储和发布器归入 `infrastructure/outbox`，普通 MyBatis 持久化入口归入 `infrastructure/persistence`。
- user-service 在 `application/event` 本地维护 `AccountCreatedPayloadV1`；Decoder 复制 Spring `ObjectMapper` 并只为
  这份 Reader 添加 mixin 与 version/traceId 适配，直接返回 `EventEnvelope<AccountCreatedPayloadV1>`。Processor 直接
  接收该信封，继续只读取事件元数据和 `accountId`。
- 删除两个机械中间包装。auth、user 各自保留同字段的本地 Payload，不增加共享业务模块或服务间编译依赖。
- 两服务的 `@MapperScan` 只扫描自己的新持久化目录，并以 `@Mapper` 注解过滤。

此 ADR 替代 ADR 0003 中“生产 Decoder 使用 JsonNode 后投影 Input”的实现细节；ADR 0003 关于通用信封、
本地业务载荷、协议边界、消息可靠性与兼容性的其他结论继续有效。

## 备选方案与取舍

- 保留 `JsonNode → DecodedAccountCreatedEvent → Input`：改动最小，但仍保留树解析、两层投影和较长调用链。
- 让 user 依赖 auth 的 Payload：可消除一个 Record，却把领域表达变成跨服务 Maven 依赖，违反数据与演进边界。
- 把兼容逻辑扩展为新的通用消息框架：可抽象更多事件，却超出当前单一事件的范围并增加配置、依赖和运维成本。

## 影响与风险

消息 JSON、路由键、交换机、表、SQL、配置键和 Outbox 投递机制均不变。局部 Reader 明确拒绝必需标识字段的
非文本值，同时保留 v1 对 `version` 的历史解释和对可选时间/生产方/traceId 的宽容处理。它不改变 HTTP
ObjectMapper，也不把 Jackson 注解加到 `EventEnvelope` 或应用层 Record。

对象绑定与旧树解析在极端 JSON 输入上可能存在边界差异。因此必须用冻结样本覆盖数值、布尔、未知字段、可选
字段类型和必需字段拒收；未覆盖的输入不能据此宣称与旧实现完全等价。

## 验证与回退

使用 JDK 17 的无冒号临时副本执行 auth/user 及公共模块的 Maven `verify`；单元测试覆盖事件最小披露、
类型化 v1 解码、非法协议拒收、MDC 恢复、处理器幂等编排以及 Mapper 包引用。隔离 MySQL、Redis、RabbitMQ
的注册、真实投递、重复消费、死信、租约竞争和启动探针仍需独立环境后验证。

回退时仅恢复本次目录和类型化代码差异或部署上一个服务制品；不删除 `auth_outbox`、`user_consumed_event`、
队列或死信记录，不清空消息、不重置投递状态，也不重放生产消息。
