# Nacos 配置与服务发现

## 当前实际配置

各服务使用 `spring.application.name` 注册；本地 YAML 显式导入：

```yaml
spring:
  config:
    # 必须保留具体 Data ID；optional 不表示其他依赖或整个启动过程可忽略。
    import: "optional:nacos:application.yml"
```

**当前 Data ID 是 `application.yml`，不是 `<service-name>.yml`。**
不要只在控制台创建 `auth-service.yml` 就期待当前导入自动读取它。
若要按服务拆分 Data ID，需作为配置变更同步修改导入、环境示例和启动测试，本次整理不实施。

Nacos 地址通过 `NACOS_SERVER_ADDR` 等现有配置注入；具体配置源、命名空间和分组以各服务 YAML
与运行环境为准，不将 `test/prod` 命名空间描述为已经创建。

## 本地使用

按 [快速开始](guides/quick-start.md) 启动基础设施。控制台地址为 `http://localhost:8848/nacos`。
当前 Compose 设置 `NACOS_AUTH_ENABLE=false`，本页不提供默认账号口令，也不把本地配置当生产认证方案。

需要远程配置时，先确认运行服务实际使用的 Namespace、Group 和导入 Data ID，再创建对应的 YAML。
共享 `application.yml` 不宜混入某一个服务的 datasource 或 JWT 专属值，以免污染其他服务。
密码、密钥和 Token 通过受保护的运行环境管理，不出现在示例或文档里。

## 配置核验

1. 检查本地导入是否完整、Nacos 地址与实例是否匹配。
2. 检查 Namespace、Group、Data ID 和格式，不以服务名推断 Data ID。
3. 从脱敏启动日志判断实际加载了哪些配置源，以及服务是否完成注册。
4. 若值未生效，检查进程环境变量、命令行参数与导入属性的具体来源；不要套用
   “远程配置永远高于环境变量”的简化优先级表。
5. `optional:` 不能保证 Nacos 不可用时服务一定能完整启动，服务发现和数据库等仍可能失败。

当前不承诺所有配置支持热刷新；连接和认证配置变更应走受控重启及探针验证。
JWT 密钥轮换属于高风险事项，需要独立授权和会话影响方案。

## 生产前置条件

启用认证、网络隔离、访问审计、配置权限和备份后才能评估共享或生产使用；本地 Compose 不具备该保证。
服务配置发生变化时至少校验 YAML、重新打包并验证启动。排错见 [故障排查](operations/troubleshooting.md)。

### Auth Outbox 快速投递配置

`auth.outbox.fast-dispatch-enabled`、线程数、队列容量、扫描间隔、积压刷新周期和停机等待时间可来自当前
实际导入的 `application.yml` 或进程环境变量；不要因为服务名是 auth 就在未改导入配置时只创建
`auth-service.yml`。这些开关会决定 Bean 和线程池是否在启动时注册，修改后使用受控重启和探针验证，
不假设 Nacos 动态刷新可以即时启停投递资源。共享配置中不得填入 JWT、数据库或 RabbitMQ 的敏感值。
