# 本地快速开始

目标是在隔离的本地环境启动认证服务与网关，并验证匿名探针。本文不承诺固定启动耗时，
也不执行旧系统数据迁移。所有命令从仓库根目录运行。

## 1. 检查工具与环境

```bash
java -version
./mvnw -version
docker compose version
docker compose config -q
```

Java 目标版本为 17。Maven 使用仓库 Wrapper，不要求另装全局 Maven。
先静态校验 Compose，再确认 Docker 守护进程可用；不要将 Docker 连接失败解释为应用故障。

## 2. 准备非公开配置

若没有 `.env`，复制 `.env.example`；已有文件不要覆盖。按实际本地环境填写密码和 JWT 密钥，
不得将真实值提交或输出到报告。环境变量清单以 [`.env.example`](../../.env.example) 为准。

- Compose 会读取 `.env`；IDE、`java -jar` 和 Maven 启动的 Java 进程不会因此自动获得变量。
- 在 IDE 的运行配置或受控 Shell 中注入各服务所需变量，尤其是 `AUTH_DB_PASSWORD`、
  `AUTH_JWT_SECRET`。不要把密钥作为命令行参数、聊天内容或示例常量。
- 第一阶段数据库可共用实例与库，但各服务连接参数需与本地 MySQL 配置匹配。
- 本地 Compose 的网络与认证设置不是生产安全配置，不可直接暴露到公网。

## 3. 启动基础设施并初始化空库

```bash
docker compose up -d
docker compose ps
```

Compose 启动 Nacos、MySQL、Redis、RabbitMQ、MinIO，**不启动 Java 业务服务，也没有挂载
业务 SQL 初始化脚本**。业务表需按 [数据库初始化指南](../database-setup-guide.md) 手动创建。
不要对共享库、旧系统库或未知实例执行初始化。

Nacos 实际导入 Data ID 为 `application.yml`，不是服务名自动拼接文件；详见
[Nacos 配置指南](../nacos-config-guide.md)。

## 4. 构建

```bash
# 路径含冒号时，由脚本先安装公共模块，再分别打包服务。
./build.sh -DskipTests
```

`-DskipTests` 跳过测试执行，通常仍编译测试，不能据此声称测试通过。
若已安装当前版本公共模块，可单独打包：

```bash
./mvnw -f service/auth-service/pom.xml package -DskipTests
./mvnw -f service/gateway-service/pom.xml package -DskipTests
```

不要直接使用根 reactor 的 `./mvnw clean package`，Linux 会把路径中的 `:` 当成 classpath 分隔符。
测试命令见 [测试指南](testing.md)。

## 5. 启动认证和网关

在 IDE 中分别运行 `AuthApplication`、`GatewayApplication`，或使用打包后的 JAR。
先启动认证服务，再启动网关；运行进程必须已注入各自环境变量。

```bash
# 在两个独立终端分别运行；版本变化后以实际 target 产物名为准。
java -jar service/auth-service/target/auth-service-0.1.0-SNAPSHOT.jar
java -jar service/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar
```

## 6. 验证入口

```bash
curl --fail-with-body http://localhost:8000/api/auth/ping
curl --fail-with-body http://localhost:8000/actuator/health
```

`ping` 成功仅证明该路由可达，不证明注册、数据库、Redis 会话和撤销链路全部正常。
完整功能验证按 [测试指南](testing.md) 执行。

| 本地入口 | 地址或端口 |
| --- | --- |
| 客户端业务入口 | `http://localhost:8000` |
| 认证服务（内部诊断） | `8100` |
| Nacos 控制台 | `http://localhost:8848/nacos` |
| RabbitMQ 管理台 | `http://localhost:15672` |
| MinIO 控制台 | `http://localhost:9001` |

## 停止与排障

只停止自己启动的 Java 进程；`docker compose stop` 可停止本地基础设施并保留数据卷。
不要为解决启动问题删除数据卷。问题按 [故障排查](../operations/troubleshooting.md) 定位。
