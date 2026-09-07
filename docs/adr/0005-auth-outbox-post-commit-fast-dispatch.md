# 0005 Auth Outbox 提交后快速投递与扫描恢复

- 状态：已接受
- 日期：2026-09-06
- 决策者：项目维护者、实施代理
- 关联任务/契约：[Auth Outbox 快速投递修改方案](../reference/auth-outbox-fast-dispatch-plan.md)、[auth.account.created.v1](../contracts/events/auth.account.created.v1.md)、[0002 Auth 与 User 资料初始化采用 Outbox 和幂等消费](0002-auth-user-profile-outbox.md)

## 背景

认证注册会在同一数据库事务中写入 `auth_account` 与 `auth_outbox`。原发布器以一秒轮询批量领取后
串行发送，正常注册事件可能等待下一轮扫描；同时批量提前设置租约会让尚未开始发送的记录已进入
`PROCESSING`，并在慢 Confirm 或进程故障时扩大恢复和重复风险。快速投递不能让 RabbitMQ 或 user-service
故障阻断注册，也不能代替持久化 Outbox 的恢复能力。

## 决策

保留账户和 Outbox 同一事务写入。注册仅在事务成功提交后把稳定 `eventId` 非阻塞交给一个有界快速
执行器；任务实际开始时才通过完整条件 UPDATE 领取记录，并在独立短事务提交后发送。总发送或快速
开关关闭时注入空通知实现，注册仍可正常写入持久化事件。

快速路径与扫描路径共用按 ID 条件领取、Confirm/return 判定、`claim_token` 条件回写和失败退避。扫描
只读发现候选 ID，逐条即时领取，不再批量提前租赁；独立扫描仍是提示被拒、丢失或服务重启后的唯一
可靠恢复通道。到期且领取次数耗尽的记录会有界收敛到 `FAILED`，不自动重放。积压聚合从发送轮次中
拆出为独立低频任务。

默认保持 `fast-dispatch-enabled=false`、扫描间隔 1 秒；快速启用、扫描降频、生产回放、切流及容量阈值
均需隔离环境测量和独立验收后另行授权。

## 备选方案与取舍

1. **只缩短扫描间隔或增大批量**：实现较简单，但不能消除正常事件的轮询等待，也会继续提前占用租约，
   因此未采用。
2. **在注册事务内同步发送 RabbitMQ**：可降低表面延迟，但把外部 Broker 故障引入注册关键路径，并破坏
   “账户与待发事件同成同败、异步恢复”的边界，因此未采用。
3. **让快速内存队列成为唯一发送通道**：进程重启、队列拒绝或任务异常会丢失唤醒，无法满足可靠投递，
   因此未采用。

## 影响与风险

不修改 HTTP API、JWT、`auth.account.created.v1` 字段、exchange、routing key、表结构、数据所有权或
消费者幂等语义；同一 `eventId` 仍可能至少一次发送，user-service 必须继续以 `(consumer_name,event_id)`
去重。

新增有限线程和按 ID 查询会带来额外资源消耗；`confirmTimeout` 不覆盖 RabbitMQ 发送、连接、数据库回写
或进程暂停，`lease > confirmTimeout` 不是租约绝对安全证明。领取次数按成功领取计数，进程在发送前崩溃
可能消耗一次次数；达到上限的记录不会自动重放，需人工受控处理。默认关闭快速路径以保留可回退基线。

## 验证与回退

代码在无冒号临时副本中通过 auth-service 单元测试，覆盖提交后提示、执行器降级、条件领取、扫描复用、
Confirm/return 与 token 条件回写。真实 MySQL 事务/并发竞争、真实 RabbitMQ Confirm/return/故障、启动探针
与性能收益仍需在资源归属明确的隔离环境验证。

首选逻辑回退为将 `AUTH_OUTBOX_FAST_DISPATCH_ENABLED=false`、保持或恢复
`AUTH_OUTBOX_POLL_INTERVAL=1s` 后受控重启；持久化记录、attempts 和消费者幂等不变。总开关关闭仅暂停
发送，不能阻止注册积累事件；不得删除 Outbox、重置 attempts 或自动重放 `FAILED`。
