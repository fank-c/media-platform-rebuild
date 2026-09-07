# 0002 Auth 与 User 资料初始化采用 Outbox 和幂等消费

- 状态：已接受
- 日期：2026-09-05
- 决策者：项目维护者、实施代理
- 关联契约：`docs/contracts/http/user-api-v1.md`、`docs/contracts/events/auth.account.created.v1.md`

## 背景

认证账户和用户资料属于不同服务及数据所有权边界。注册成功后需要初始化资料，但同步调用会扩大注册
故障面，事务提交后直接发消息又会在进程故障窗口丢失事件。同时，首次资料操作需要容忍异步延迟，
并保护停用和逻辑删除记录不被重新创建。

## 决策

auth 在注册本地事务同时写入 `auth_account` 与 `auth_outbox`，事务外以租约、有界重试和 RabbitMQ
publisher confirm 投递 `auth.account.created.v1`。user 以 `user_consumed_event` 幂等登记并在同一事务
初始化物理缺失资料。GET `/me` 纯读并对缺失资料返回 PENDING；首次 PATCH 才允许兜底建档。所有编辑
使用 revision 条件更新。初始化不覆盖 ACTIVE 资料，也不恢复 DISABLED 或逻辑删除资料。

已有新认证库账号通过 auth 内部、默认关闭且默认 dry-run 的补齐任务生成同版本事件；不读取旧单体。

## 备选方案与取舍

- 注册后直接同步调用 user：实现简单，但把 user 故障和延迟传递给注册，且形成同步耦合。
- 事务提交后直接发 RabbitMQ：没有数据库与 Broker 原子性，进程退出会永久丢事件。
- GET 缺失时建档：会让查询产生隐式写入，爬取、重试和只读调用均可能改变状态。
- 分布式事务：复杂度和运维成本超过当前阶段需求。

## 影响与风险

方案增加 Outbox、消费幂等和补齐进度表，以及调度和死信运维责任。至少一次投递可能重复消息，必须
长期保持消费幂等。RabbitMQ 故障期间资料初始化会延迟，但注册和已有资料 HTTP 读写可继续工作。
业务服务仍依赖可信网关网络边界；未验证隔离前不得对公网直接开放 8200。

最低可观测指标注册到 Micrometer `MeterRegistry`，由目标环境配置的受控监控出口采集，不新增公网管理端点：
`auth_outbox_publish_total` 按发布结果统计，`auth_outbox_pending`、`auth_outbox_failed` 和
`auth_outbox_oldest_unpublished_age_seconds` 提供积压快照，`auth_profile_backfill_total` 按候选、入队、跳过和
失败统计，`user_account_created_consume_total` 按创建、已存在、停用/墓碑跳过、重复和失败尝试统计，
`user_profile_revision_conflict_total` 统计资料条件更新冲突。指标只使用固定结果标签，
不得将 accountId、eventId 或 traceId 作为标签。

## 验证与回退

验证账户与 Outbox 同成同败、Broker 故障恢复、不可路由、重复消息、事件/PATCH/补齐竞争、GET 无写入、
revision 冲突以及停用/删除保护。回退时可暂停发布器或消费者并保留 Outbox、队列、死信和新增表；应用
回滚不删除数据。生产补齐、消息重放和路由切换仍需单独授权。
