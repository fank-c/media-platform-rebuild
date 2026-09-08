# 契约管理

本目录存放跨边界、需要被独立版本管理的契约：对外 HTTP API、服务间同步 API 和
RabbitMQ 领域事件。业务实现代码不能代替契约文档。

## 目录约定

```text
docs/contracts/
  http/                 OpenAPI 定义或接口变更说明
  events/               事件信封与具体事件定义
```

新增契约时创建相应子目录。文件名使用领域和版本，例如
`http/auth-api-v1.md`、`events/content.published.v1.md`。当 OpenAPI 工具链引入后，
HTTP 契约优先使用 OpenAPI YAML/JSON；在此之前使用 Markdown 说明并保留请求、响应和错误
示例。

## 必填内容

每个契约至少说明：

- 所有者服务、消费者/调用方和生效日期。
- 请求或消息字段、字段类型、必填性、默认值和敏感字段处理方式。
- 鉴权要求、错误语义、幂等要求、超时/重试要求（若适用）。
- 当前版本、兼容性影响、废弃计划和删除条件。
- 对事件：路由键、生产方、消费方、幂等键、重试与死信策略。

## 演进规则

- 仅新增可选字段、放宽限制或新增独立接口/事件版本，通常可视为向后兼容；仍须更新契约。
- 删除或重命名字段、改变字段语义/类型、收紧校验、改变鉴权规则或改写错误语义，均为
  破坏性变更。必须发布新版本，保留旧版本兼容期，并记录所有已知消费者和下线日期。
- 生产者不能在未知消费者尚未完成兼容前停止发送旧事件；消费者须忽略未知字段。
- JWT Claims 也是跨服务契约。新增 Claim 可兼容，修改既有 Claim 语义或移除 Claim 必须按
  破坏性变更处理。

## 当前契约索引

| 契约 | 所有者 | 已知调用方 | 状态 |
| --- | --- | --- | --- |
| [认证 HTTP API v1（含 JWT Claims）](http/auth-api-v1.md) | auth-service | 新客户端、gateway-service | 现有实现文档；补录接口和差异待完整验收 |
| [用户资料 HTTP API v1](http/user-api-v1.md) | user-service | 新客户端、平台管理端 | 已实现，待独立验收 |
| [账号创建事件 v1](events/auth.account.created.v1.md) | auth-service | user-service | 已实现，待集成与故障验收 |

消息通用规范见 [消息设计](../reference/messaging.md)；只有本索引登记的事件才属于已发布契约。

| [文件 HTTP v1](http/file-api-v1.md) | file-service | 新客户端 | 已实施，待独立验收；不含公开引用或跨服务读取 |
