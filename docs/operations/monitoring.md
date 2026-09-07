# 可观测性现状与验收要求

## 当前入口

服务 YAML 当前配置 Actuator 暴露 `health,info`。可在本地通过网关检查：

```bash
curl --fail-with-body http://localhost:8000/actuator/health
```

该响应反映网关自身健康，不等于所有业务下游链路正常；认证连通性另测 `/api/auth/ping`，
完整认证测试见 [测试指南](../guides/testing.md)。内部服务探针仅供受控运维访问。

当前仓库未提供完整 Prometheus/Grafana、集中日志或 Jaeger/Zipkin 部署配置，
也未据 POM 确认 Prometheus registry 接入；不能直接宣称 `/actuator/prometheus` 可用。
本页列出的是后续验收要求，不是已经采集的监控清单。

## 最少应补的指标

| 边界 | 指标方向 | 失败入口 |
| --- | --- | --- |
| 网关认证 | 放行/拒绝量、回源延迟、缓存命中、读写/删除失败 | 区分客户端凭据错误与依赖故障 |
| 认证服务 | 注册/登录/刷新/注销成功失败、会话操作延迟 | Redis 不可用、刷新重放、异常注册冲突 |
| 数据访问 | 连接池等待、查询错误和延迟 | 数据库连接、结构不一致 |
| 后续消息链路 | 发布/消费成功失败、重试、死信与积压 | 有界重试耗尽后的可观测出口 |

阈值需结合实际容量和基线确定，不能从模板复制成生产告警。指标标签不能使用完整 Token、
用户名、邮箱、手机号或高基数会话标识。

## 日志与追踪要求

所有入站 HTTP 和事件都应携带或生成 `traceId` 并向下游传播；这是项目要求，
当前不宣称完整链路已实现。新增入口需同步设计追踪和成功/失败或延迟指标。
日志只保留必要的非敏感诊断信息，禁止打印请求凭据、密码、完整 JWT 和真实用户资料。

管理端点不得公开环境变量、配置、堆转储或敏感健康详情。需要新监控基础设施时先审查并获得授权。

## 验收

分别验证健康探针、成功/失败业务请求、依赖超时、日志脱敏和跨服务 traceId；
记录实际可访问入口与缺失指标，不以“引入 Actuator”替代端到端可观测性验收。
故障处理顺序见 [故障排查](troubleshooting.md)。

## Auth Outbox 快速投递观测

认证服务新增的快速投递观测只使用固定低基数 outcome，不使用 `eventId`、账户 ID、`traceId` 或异常正文作
指标标签。重点区分“快速提示已接收”与“消息已发布”：前者只说明任务进入内存执行器，后者必须同时满足
RabbitMQ Confirm ack、无 return 和数据库 `PUBLISHED` 条件回写。

可从受控的应用指标出口观察 `auth_outbox_fast_dispatch_hint_total`、`auth_outbox_claim_total`、
`auth_outbox_attempts_exhausted_total`、`auth_outbox_scan_duration`、`auth_outbox_backlog_refresh_total`，以及
既有的积压、失败数和最老未发布年龄 Gauge。积压快照每 60 秒独立刷新；刷新失败保留上次有效值并记录失败，
不会改变发送状态。建议在隔离环境根据基线设置快速拒绝持续、`FAILED>0`、最老未发布年龄和快照陈旧的告警，
不要将默认参数写成已验证的生产阈值。
