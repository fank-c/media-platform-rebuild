# Media Platform

Calles 视频平台的微服务重构工程。原单体工程保留在相邻的 `calles` 目录，本目录用于渐进式迁移和新功能开发。

## 技术基线

- Java 17（可使用本机 JDK 21 编译）
- Spring Boot 3.3 + Spring Cloud 2023
- Nacos：服务发现与配置中心
- Spring Cloud Gateway：统一入口
- 自定义 JWT：认证服务签发令牌，网关和业务服务校验
- RabbitMQ：审核、推荐等异步领域事件
- MySQL：第一阶段单实例逻辑隔离，后续按服务物理拆分
- Redis：缓存、限流和推荐计算数据
- MinIO：视频及图片对象存储
- MyBatis-Plus：延续原项目的数据访问方式

## 目录

```text
common/
  common-core/       通用响应契约和基础类型
  common-web/        Web 层通用依赖
service/
  gateway-service/   统一网关，端口 8000
  auth-service/      认证服务，端口 8100
  user-service/      用户服务，端口 8200
  content-service/   视频、标签和文件元数据，端口 8300
  audit-service/     审核服务，端口 8400
  interaction-service/ 互动服务，端口 8500
  recommend-service/ 推荐服务，端口 8600
docker-compose.yml   本地基础设施
docs/                架构设计和迁移计划
AGENTS.md            AI/自动化协作规则
```

## 本地启动

1. 启动基础设施：

   ```bash
   cp .env.example .env
   docker compose up -d
   ```

2. 构建全部模块：

   ```bash
   ./build.sh -DskipTests
   ```

   `build.sh` 会先安装公共模块，再逐个构建服务。这是因为当前目录名包含
   `:`，而 Linux/Java 使用 `:` 分隔 classpath；直接执行根工程的 reactor
   构建会导致公共模块依赖路径被错误拆分。若将目录改为不含冒号的名称，脚本
   会自动切换为标准的根工程构建。

3. 在各服务目录运行对应的 Spring Boot 启动类。服务默认连接 `localhost` 上的基础设施，也可以通过环境变量覆盖连接地址。

网关地址为 `http://localhost:8000`，Nacos 控制台为 `http://localhost:8848/nacos`，RabbitMQ 管理台为 `http://localhost:15672`。

## 迁移原则

先迁移认证和网关，再迁移用户、内容、审核和互动，最后迁移推荐。服务之间通过 API 或领域事件通信，公共模块不共享实体和数据库访问代码。迁移期间保留原接口适配层，待流量切换稳定后再删除单体实现。

## 数据库隔离

第一阶段使用同一 MySQL 实例和数据库，通过服务专属表前缀（`auth_`、`user_`、
`content_`、`audit_`、`interaction_`、`recommend_`）实现逻辑隔离。服务只能访问
自己的表，跨域数据通过 API 或 RabbitMQ 事件获取；后续再按服务迁移到独立数据库。

## 文档

- [架构设计](docs/architecture.md)：服务边界、JWT、Nacos、RabbitMQ 和数据所有权约定。
- [迁移计划](docs/migration-plan.md)：渐进式迁移阶段、切流条件和回退策略。
- [契约管理](docs/contracts/README.md)：API、事件与 JWT 声明的版本化和兼容性规则。
- [架构决策记录](docs/adr/README.md)：重大架构变更的 ADR 规范和模板。
- [功能点协作回合](docs/collaboration-workflow.md)：讨论、方案、审查、实施和验收的交接流程。
- [AI 协作规则](AGENTS.md)：自动化编码代理在本工程内必须遵守的边界、构建和安全要求。
