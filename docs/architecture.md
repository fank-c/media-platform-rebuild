# 微服务架构设计

## 1. 目标与约束

本工程将相邻 `calles` 单体应用逐步重构为视频媒体平台微服务。重构期间必须保留
旧系统可运行和可回退，不能通过一次性复制代码或一次性迁移数据完成切换。

已确认的技术基线：Java 17、Spring Boot 3.3、Spring Cloud 2023、Nacos、RabbitMQ、
MySQL、Redis、MinIO、MyBatis-Plus 和 `java-jwt`。基础设施通过
`docker-compose.yml` 提供本地开发环境。

当前工程只完成骨架和连通性占位，尚未迁移单体业务接口、数据库表、JWT 签发逻辑或
RabbitMQ 消费者。本文区分“当前已实现”和“目标设计”，避免将规划误作现状。

## 2. 系统边界

```text
Client
  |
  v
Gateway Service (8000) ---- Nacos: discovery/config
  |        |        |\
  |        |        | +-- Auth Service (8100)
  |        |        +---- User Service (8200)
  |        +------------- Content Service (8300) ---- MinIO
  +---------------------- Interaction Service (8500)
                           |
Content Service -------- RabbitMQ --------> Audit Service (8400)
Interaction Service ---- RabbitMQ --------> Recommend Service (8600)
                                      |
                                    Redis

All business services ---> one MySQL instance initially, with logical ownership
```

网关是客户端唯一入口。服务间的同步调用只用于必须即时返回且数据量有限的场景；
审核、推荐更新等不要求同步完成的动作通过 RabbitMQ 领域事件解耦。

## 3. 服务职责

| 服务 | 端口 | 负责范围 | 数据所有权 | 当前状态 |
| --- | ---: | --- | --- | --- |
| gateway-service | 8000 | 路由、CORS、认证前置校验、限流和灰度入口 | 无业务表 | 骨架与路由已配置 |
| auth-service | 8100 | 注册/登录协作、JWT 签发刷新注销、会话失效 | `auth_*` | 健康接口占位 |
| user-service | 8200 | 账户资料、关系、用户设置 | `user_*` | 骨架 |
| content-service | 8300 | 视频、标签、分类、文件元数据、上传编排 | `content_*` | 骨架 |
| audit-service | 8400 | 内容审核任务、审核结果与人工处理 | `audit_*` | 骨架 |
| interaction-service | 8500 | 点赞、收藏、评论、关注、历史、分享 | `interaction_*` | 骨架 |
| recommend-service | 8600 | 用户画像、候选集、推荐结果 | `recommend_*` | 骨架 |

服务不共享 JPA/MyBatis 实体、Mapper、Service 实现或数据库表。`common` 只能承载
协议和横切基础能力，不能成为共享业务模块。

## 4. 公共模块边界

```text
business service -> common-web -> common-core
```

- `common-core`：统一响应、错误码、基础异常、无业务含义的 DTO/事件信封。
- `common-web`：HTTP 相关横切能力，例如全局异常处理、请求 ID、校验和认证上下文。

禁止放入 `common` 的内容包括用户、视频、审核等领域实体，Mapper，跨服务数据库访问，
以及任一具体服务的业务规则。

## 5. JWT 认证设计

JWT 由 `auth-service` 签发；网关进行第一道校验，业务服务仍需对来自网关的身份信息
进行签名校验或在受信任网络中验证，不能只信任普通 HTTP Header。初始实现使用
`java-jwt` 和 HMAC-SHA256；签名密钥只从环境变量或 Nacos 受保护配置读取，绝不提交到
仓库。未来如需多方验签，可迁移为 RSA/EC 非对称签名而不改变 Token 声明约定。

访问令牌应至少包含以下声明：

| 声明 | 含义 |
| --- | --- |
| `sub` | 用户或管理员的稳定 ID |
| `typ` | 主体类型，例如 `user`、`admin` |
| `roles` | 授权角色集合 |
| `jti` | Token 唯一 ID，用于注销和审计 |
| `iss` / `aud` | 签发方和受众，固定为本平台约定值 |
| `iat` / `exp` | 签发与过期时间 |

访问令牌短期有效；刷新令牌使用随机、不透明值，以哈希形式存入 Redis，并绑定主体、设备
和过期时间。登出、改密、禁用账号时删除对应刷新会话，并将未过期访问令牌的 `jti` 标记
为失效。客户端统一通过 `Authorization: Bearer <access-token>` 传递令牌，不兼容旧系统
的自定义 Header 时，由迁移适配层临时转换。

## 6. Nacos 配置与服务发现

- 每个服务以 `spring.application.name` 注册到 Nacos。
- 本地 `application.yml` 保留启动所需的默认值；Nacos 用于环境差异配置和共享非敏感配置。
- 当前导入形式为 `optional:nacos:application.yml`。`optional` 不等于空 dataId，不能写成
  未指定 dataId 的 `optional:nacos:`。
- 数据库密码、JWT 签名密钥、对象存储密钥等敏感配置只可在运行环境提供，不能放入
  `.env.example`、Nacos 示例配置或代码。

## 7. RabbitMQ 事件设计

所有领域事件发送至主题交换机 `media.platform.events`。路由键采用
`<domain>.<event>.<version>`，例如 `content.published.v1`、`interaction.liked.v1`。
事件信封的建议字段如下：

```json
{
  "eventId": "uuid",
  "eventType": "content.published.v1",
  "occurredAt": "2026-08-31T00:00:00Z",
  "producer": "content-service",
  "aggregateId": "123",
  "traceId": "trace-id",
  "payload": {}
}
```

消费者必须以 `eventId` 幂等处理。涉及数据库变更和消息发布的业务，落地时采用 Outbox
模式或等价的可靠投递机制；失败消息进入重试队列，超过阈值后进入死信队列并可观测告警。
禁止把 RabbitMQ 当作远程 RPC 或传递完整领域实体。

## 8. 数据与存储设计

第一阶段使用同一个 MySQL 实例和 `media_platform` 数据库，但数据所有权按服务严格隔离。
新建表必须使用服务前缀：`auth_`、`user_`、`content_`、`audit_`、`interaction_`、
`recommend_`。一个服务只持有和迁移自己的表；跨域查询通过服务 API、只读投影或事件
同步，不允许跨服务 SQL Join。

旧单体表在迁移前先登记“当前拥有者、目标表、读写切换时间和回退方式”。必要时创建
服务专属新表并双写/回填，验证后再切流；不要求也不允许在第一阶段批量重命名全部旧表。

Redis 只保存缓存、限流计数、会话/Token 失效状态和推荐计算数据，不能成为唯一业务事实
来源。MinIO 只保存对象本体，文件元数据和授权状态属于 `content-service`。

## 9. 接口与可观测性约定

- 外部 API 由网关暴露，路径使用 `/api/<domain>/...`；内部服务地址不直接暴露给客户端。
- 统一响应结构由 `ApiResponse<T>` 承载；错误码和 HTTP 状态需同时语义正确。
- 所有入站请求和事件都应携带或生成 `traceId`，并写入结构化日志。
- Spring Boot Actuator 至少暴露 `health`、`info`；生产环境不公开敏感端点。
- 破坏性 API 变更必须先版本化或提供适配期，不能直接改写仍由单体客户端使用的契约。

## 10. 非目标

在首个迁移阶段，不引入服务网格、分布式事务框架、Kubernetes、独立数据库实例、
复杂 CQRS 或多区域部署。只有在已有服务边界、流量和运维需求验证后，才评估这些投入。
