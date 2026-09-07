# Media Platform

Calles 视频平台的渐进式微服务重构工程。相邻 `../calles` 是只读迁移来源；本工程的新增实现
不代表旧系统已经迁移或完成切流。

## 当前能做什么

- 截至 2026-09-07，用户确认 auth/user 第一阶段功能收尾；本次整理按源码核对交付范围，不补写未执行的验收结果。
- auth：注册、登录、刷新、注销、Token 验证、当前账号查询，以及 Redis 两阶段刷新，避免退出后的会话被在途刷新重新创建。
- auth/user 消息链：注册事务登记 Outbox、提交后可选快速投递、扫描恢复，以及 user 幂等消费和资料初始化；快速投递默认关闭。
- user：本人资料查询/编辑、revision 并发控制、公开/批量摘要及管理端资料查询/编辑；不包含头像上传和账号启停。
- 网关已有路由、认证拦截、验证缓存和身份 Header 下传；内容、审核、互动、推荐仍主要是骨架。
- 第一阶段收尾不等于生产可用或旧系统已迁移；旧账号兼容、切流及真实依赖独立验收仍保留门槛。

详细阶段范围见 [功能清单](docs/TODO.md)，验证记录见 [测试指南](docs/guides/testing.md)。以上不是本轮运行验收。能力边界见 [架构设计](docs/architecture.md)，
风险与验证缺口见 [认证设计](docs/reference/authentication.md)。

## 工程布局与技术基线

| 位置 | 职责 |
| --- | --- |
| `common/common-core` | 无业务含义的响应与基础契约 |
| `common/common-web` | Web 横切能力和请求身份上下文 |
| `service` | 网关及认证、用户、内容、审核、互动、推荐服务 |
| `db/init` | 当前业务表的空库 Schema 快照 |
| `service/<service-name>/db/schema` | 服务私有、按表拆分的 Schema 定义 |
| `docker-compose.yml` | 本地基础设施，不包含业务应用容器 |
| `docs` | 指南、设计、契约与运维说明 |

目标 Java 17，沿用 Spring Boot 3.3、Spring Cloud 2023、MyBatis-Plus；
基础设施为 Nacos、MySQL、Redis、RabbitMQ、MinIO。精确版本以 POM 和 Compose 为准。

## 从哪里开始

1. [快速开始](docs/guides/quick-start.md)：环境准备、手动建表、构建和网关探针。
2. [文档中心](docs/README.md)：按任务选择资料，了解各主题的唯一维护位置。
3. [开发指南](docs/guides/development.md)：变更流程与交付要求。
4. [迁移计划](docs/migration-plan.md)：阶段门槛、兼容和回退约束。

Linux 下当前路径包含 `:`，全量构建使用 `./build.sh -DskipTests`，不要直接执行根 reactor
的 `./mvnw clean package`。完整说明集中在快速开始，不在各文档重复维护启动步骤。

AI 协作以 [AGENTS.md](AGENTS.md) 为准；[CLAUDE.md](CLAUDE.md) 保留为对应工具的协作入口。
