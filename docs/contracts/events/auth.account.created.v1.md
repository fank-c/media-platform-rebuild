# auth.account.created.v1

- 生产方：`auth-service`
- 消费方：`user-service`
- 生效日期：2026-09-05
- Exchange：`media.platform.events`（Topic、durable）
- Routing Key：`auth.account.created.v1`
- Queue：`user-service.auth-account-created.v1`
- 幂等键：`eventId`

## 消息体

```json
{
  "eventId": "稳定的事件UUID",
  "eventType": "auth.account.created",
  "version": 1,
  "occurredAt": "2026-09-05T10:30:00.000Z",
  "producer": "auth-service",
  "aggregateId": "32位accountId",
  "traceId": "trace-id",
  "payload": {
    "accountId": "32位accountId",
    "accountType": "user",
    "createdAt": "2026-09-05T10:30:00.000Z"
  }
}
```

`aggregateId` 必须等于 `payload.accountId`；主体 ID 为 32 位 UUID。事件不包含登录名、邮箱、密码、
Token 或认证实体。消费者必须容忍未知字段，但不支持的版本不得按 v1 推断处理。

## v1 类型与兼容边界

`eventId`、`eventType`、`aggregateId`、`payload.accountId` 与 `payload.accountType` 必须以 JSON 字符串
出现，空字符串或其他 JSON 类型均为非法事件。`traceId` 不是业务合法性的前置条件：缺失、非字符串或
不符合安全格式时，消费者以 AMQP `messageId` 或新 UUID 仅为日志追踪兜底，仍按其他字段处理事件。

为保持既有消费者行为，`version` 使用 Jackson 的 `asInt(-1)` 解释后必须等于 `1`。因此 JSON 数字
`1`、字符串 `"1"`、布尔值 `true`、数值 `1.9` 与字符串 `"1.9"` 当前都会被解释为 v1；缺失、`null`、
对象、数组或解释结果不为 `1` 的值会被拒绝。该宽松边界是 v1 已有行为，不代表新事件版本应继续采用它。

`producer`、`occurredAt` 与 `payload.createdAt` 保留在消息体中供审计和演进使用，但当前 user v1 消费者
不把它们作为拒收条件。字段顺序不是契约；字段名、值类型和上述消费结果才是兼容边界。

## 本地类型化实现约束

`common-core` 仅提供无业务含义的 `EventEnvelope<T>`。auth-service 与 user-service 分别维护同字段的本地
`AccountCreatedPayloadV1`，不得让 user 编译依赖 auth 的 Payload。user 的入站 Decoder 使用独立的局部
Jackson Reader 直接绑定 `EventEnvelope<AccountCreatedPayloadV1>`；应用处理器不依赖 `JsonNode`、`ObjectMapper`
或 AMQP Message。该实现约束不改变本节已有字段、接收语义或版本规则。

## 可靠性和失败语义

auth 在账户本地事务中写入 `auth_outbox`，事务外使用 publisher confirm 投递，因此提供至少一次而非
恰好一次语义。同一 Outbox 重试复用 eventId。user 使用 `(consumer_name,event_id)` 唯一键幂等，
并与资料初始化处于同一事务。

临时消费失败由监听容器按 `USER_ACCOUNT_CREATED_MAX_ATTEMPTS` 有限重试（默认 3 次）；非法版本、非法主体或重试耗尽进入
`user-service.auth-account-created.v1.dlq`。死信不设置自动过期，重放必须保留原 eventId 并另行授权。

## 演进

新增可选字段兼容 v1；删除字段、改变含义或类型需发布新 routing key 和版本，并在全部消费者迁移前
继续发布旧版本。

### 投递调度说明

生产方可在账户与 Outbox 的本地事务提交后使用内存快速提示缩短正常等待，但这不改变本消息的字段、
序列化、exchange、routing key、`messageId=eventId` 或至少一次语义。快速提示被拒、丢失或服务重启时，
生产方会由持久化 Outbox 扫描恢复；同一 `eventId` 仍可能重复投递，消费者必须继续执行既有幂等处理。
