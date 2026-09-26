# 架构决策记录 (ADR)

这里记录“为什么这样选”：背景、决策、放弃的方案和代价。系统当前**是什么样**，写在 `docs/modules/`。

## 规则

- 什么时候必须写：新增服务、改变数据所有权、共享模块、基础设施或新的第三方依赖、同步调用、跨服务事件、JWT 策略、双写切流、框架替换（见 `AGENTS.md`）。
- 文件名用 `NNNN-主题.md`，编号递增，不复用。
- 结构：状态 / 背景 / 决策 / 备选方案 / 后果，可选加修订记录。
- 只写决策和约束。阈值、字段、SQL 这类实现细节放在模块文档里，从 ADR 链接过去。
- 已采纳的 ADR 不改结论。要改方向就写一篇新的 ADR，把旧的状态标为 `Superseded by NNNN`（已被 NNNN 取代）。补充说明写进“修订记录”。
- 已经在代码里落地、事后才补的记录，状态标为“已采纳（追认）”。

## 索引

| 编号 | 主题 | 状态 |
| :--- | :--- | :--- |
| [0001](0001-interaction-outbox-and-video-action-events.md) | 互动模块自属 Outbox 与统一视频交互事件 | 已采纳 |
| [0002](0002-object-storage-presigned-url-exception.md) | 对象存储预签名直传与下载作为网关的受控例外 | 已采纳（追认） |
| [0003](0003-interaction-redisson-distributed-lock.md) | 互动服务引入 Redisson 分布式锁串行化观看心跳 | 已采纳（追认） |
| [0004](0004-interaction-counter-deltas.md) | 互动公开计数采用事务内增量与后台汇总 | 已采纳 |

## 待补

下面这些决策已经在代码里落地，但还没有 ADR：

- 认证 Outbox 与 `auth.account.created` 事件
- 用户关注事件 `user.relation.*.v1` 与用户 Outbox
- 视频计数的所有权从 content 移到 interaction
- 推荐服务引入 Qdrant 向量库
