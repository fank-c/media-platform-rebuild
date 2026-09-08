# 微服务架构设计

## 1. 目标与约束

本工程将相邻 `calles` 单体应用逐步重构为视频媒体平台微服务。重构期间必须保留
旧系统可运行和可回退，不能通过一次性复制代码或一次性迁移数据完成切换。

已确认的技术基线：Java 17、Spring Boot 3.3、Spring Cloud 2023、Nacos、RabbitMQ、
MySQL、Redis、MinIO、MyBatis-Plus 和 `java-jwt`。基础设施通过
`docker-compose.yml` 提供本地开发环境。

当前工程按领域逐步迁移；2026-09-07 用户确认 auth/user 第一阶段功能收尾：认证基础用例、
Redis 两阶段刷新、Outbox 投递与用户资料查询/编辑和幂等初始化已有实现，网关已有认证拦截与缓存代码。
这不包含旧账号兼容、其他领域闭环、切流与完整独立运行验收。本文区分
“当前已实现”和“目标设计”，代码存在不等于领域已迁移。

阅读入口见 [文档中心](README.md)；接口字段以 [认证契约](contracts/http/auth-api-v1.md) 为准，
认证具体流程、历史证据和风险集中在 [认证设计](reference/authentication.md)。

## 2. 系统边界（目标拓扑）

```text
Client
  |
  v
Gateway Service (8000) ---- Nacos: discovery/config
  |        |        |\
  |        |        | +-- Auth Service (8100)
  |        |        +---- User Service (8200)
  |        +------------- Content Service (8300)
  |        +------------- File Service (8700) ------- MinIO (私有 bucket，签名数据流例外)
  +---------------------- Interaction Service (8500)
                           |
Content Service -------- RabbitMQ --------> Audit Service (8400)
Interaction Service ---- RabbitMQ --------> Recommend Service (8600)
                                      |
                                    Redis

All business services ---> one MySQL instance initially, with logical ownership
```

上图中的内容、审核、互动、推荐和对象存储业务连线是目标关系，不表示业务链路已上线。

网关是客户端唯一入口。服务间的同步调用只用于必须即时返回且数据量有限的场景；
审核、推荐更新等不要求同步完成的动作通过 RabbitMQ 领域事件解耦。

## 3. 服务职责

| 服务 | 端口 | 负责范围 | 数据所有权 | 当前状态 |
| --- | ---: | --- | --- | --- |
| gateway-service | 8000 | 路由、CORS、JWT 认证前置校验、身份下传、限流和灰度入口 | 无业务表 | 路由与认证拦截已实现；切流和限流仍待后续 |
| auth-service | 8100 | 登录、JWT 签发刷新注销、会话失效 | `auth_*` | 注册及认证用例已有代码；旧账号兼容与完整验收待完成 |
| user-service | 8200 | 账户资料、关系、用户设置 | `user_*` | 骨架与资料表 SQL；资料业务闭环未完成 |
| content-service | 8300 | 视频、标签、分类和未来业务引用 | `content_*` | 骨架；不再拥有通用文件元数据或对象存储编排 |
| file-service | 8700 | 私人文件元数据、上传编排、MinIO 适配与短期签名 | `file_*` | 第一阶段已实现，待独立验收；未开放跨服务读取、公开引用或真实环境直传 |
| audit-service | 8400 | 内容审核任务、审核结果与人工处理 | `audit_*` | 骨架 |
| interaction-service | 8500 | 点赞、收藏、评论、关注、历史、分享 | `interaction_*` | 骨架 |
| recommend-service | 8600 | 用户画像、候选集、推荐结果 | `recommend_*` | 骨架 |

服务不共享 JPA/MyBatis 实体、Mapper、Service 实现或数据库表。`common` 只能承载
协议和横切基础能力，不能成为共享业务模块。`common-web` 通过 Spring Boot 自动配置在
Servlet 业务服务中注册 `UserContextFilter`，仅解析网关注入的身份 Header；它不负责 JWT 验签。

## 4. 公共模块边界

```text
business service -> common-web -> common-core
```

- `common-core`：统一响应、错误码、基础异常、无业务含义的 DTO/事件信封。当前
  `EventEnvelope<T>` 只提供事件公共元数据容器；认证等领域 Payload 由各服务本地维护。当前
  auth 的事件工厂写入 Outbox，user 的协议适配器直接绑定为 `EventEnvelope<本地Payload>` 后交给事务处理器，
  不共享领域 DTO、JSON 配置或消息框架对象。
- `common-web`：HTTP 相关横切能力，例如全局异常处理、请求 ID、校验和认证上下文。

禁止放入 `common` 的内容包括用户、视频、审核等领域实体，Mapper，跨服务数据库访问，
以及任一具体服务的业务规则。

## 5. JWT 认证设计

JWT 由 `auth-service` 签发；网关进行第一道校验，业务服务仍需对来自网关的身份信息
进行签名校验或在受信任网络中验证，不能只信任普通 HTTP Header。初始实现使用
`java-jwt` 和 HMAC-SHA256；签名密钥只从环境变量或 Nacos 受保护配置读取，绝不提交到
仓库。未来如需多方验签，可迁移为 RSA/EC 非对称签名而不改变 Token 声明约定。

JWT Claims、刷新凭据、错误与兼容性以 [认证 HTTP v1](contracts/http/auth-api-v1.md) 为唯一详细定义。
网关通过认证服务 `/verify` 回源验证，并缓存 Token 摘要对应的验证结果；它不是本地 JWT 验签器。
common-web 只解析身份头，因此业务服务的可信网络隔离仍是必要前提，不能将身份解析描述为独立验签。

网关注销后会尝试清理缓存，但缓存删除失败、直连注销和并发回填仍有撤销窗口；
禁用状态也不由 `/verify` 实时查库。完整说明及待补独立 ADR 见 [认证设计](reference/authentication.md)。

## 6. Nacos 配置与服务发现

- 每个服务以 `spring.application.name` 注册到 Nacos。
- 本地 `application.yml` 保留启动所需的默认值；Nacos 用于环境差异配置和共享非敏感配置。
- 当前导入形式为 `optional:nacos:application.yml`。`optional` 不等于空 dataId，不能写成
  未指定 dataId 的 `optional:nacos:`。
- 数据库密码、JWT 签名密钥、对象存储密钥等敏感配置只可在运行环境提供，不能放入
  `.env.example`、Nacos 示例配置或代码。

## 7. RabbitMQ 事件设计（目标）

非即时业务通过版本化事件解耦，目标交换机 `media.platform.events`、路由键
`<domain>.<event>.<version>`。具体信封、Outbox、幂等、重试与死信要求统一见
[消息设计](reference/messaging.md)。auth 到 user 的 `auth.account.created.v1` 已有本地 Outbox、类型化
协议适配和消费幂等代码，但隔离环境中的真实投递、死信与恢复验证仍待完成，不能据此标记用户领域已迁移。

## 8. 数据与存储设计

第一阶段使用同一个 MySQL 实例和 `media_platform` 数据库，但数据所有权按服务严格隔离。
新建表必须使用服务前缀：`auth_`、`user_`、`content_`、`audit_`、`interaction_`、
`recommend_`。一个服务只持有和迁移自己的表；跨域查询通过服务 API、只读投影或事件
同步，不允许跨服务 SQL Join。

### 8.1 初始认证与用户资料表

| 表 | 精确所有者 | 职责 | 关联与访问边界 |
| --- | --- | --- | --- |
| `auth_account` | `auth-service` | 登录名、BCrypt 密码哈希、角色、认证状态和逻辑删除标记 | 仅 `auth-service` 可读写；`status` 决定能否认证，`deleted` 由服务内 Mapper 过滤。 |
| `auth_outbox`、`auth_profile_backfill_progress` | `auth-service` | 认证领域事件可靠发布及新认证库账号补齐进度 | 不读取或写入 user-service 表；补齐默认关闭且默认 dry-run。 |
| `user_profile`、`user_consumed_event` | `user-service` | 资料、并发版本及账号创建事件消费幂等 | `account_id` 仅逻辑关联 `auth_account.id`；资料状态不替代认证状态，关注关系属于 interaction-service。 |

`auth_account.id` 使用 MyBatis-Plus `ASSIGN_UUID` 生成的 32 位字符串 UUID；`user_profile.account_id`
必须使用该认证主体 ID，资料服务不得自行生成不关联认证主体的标识。

空库使用 `db/init/schema.sql` 初始化当前 Schema；各服务 `db/schema/` 中按表拆分的 DDL 作为所属服务的源码入口，
两者必须在同一变更中保持一致。后续已建库环境的结构演进须另行设计并提交可审查迁移脚本。Compose 当前不挂载初始化 SQL，需要按
[数据库初始化指南](database-setup-guide.md) 手动执行；字段及 Redis 所有权见
[数据库参考](reference/database-schema.md)。

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

## 11. 当前网关路由

以下路由以本地 gateway-service YAML 为据；有路由不代表下游业务接口已经实现。

| 路径 | 目标服务 |
| --- | --- |
| `/api/auth/**` | auth-service |
| `/api/users/**` | user-service |
| `/api/content/**` | content-service |
| `/api/files/**` | file-service |
| `/api/audit/**` | audit-service |
| `/api/interactions/**` | interaction-service |
| `/api/recommend/**` | recommend-service |

当前没有旧单体兜底或切流开关配置；迁移计划中的切回单体属于待设计和演练的目标操作。
