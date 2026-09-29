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
| [0003](0003-interaction-redisson-distributed-lock.md) | 互动服务引入 Redisson 分布式锁串行化观看心跳 | 已被 [0005](0005-观看能力拆分与视频时长本地快照.md) 取代 |
| [0004](0004-interaction-counter-deltas.md) | 互动公开计数采用事务内增量与后台汇总 | 已采纳 |
| [0005](0005-观看能力拆分与视频时长本地快照.md) | 观看能力按进度 / 会话 / 资格 / 凭据拆分，并使用视频时长本地快照 | 已采纳 |
| [0006](0006-auth-outbox-and-account-created-event.md) | 认证模块自属 Outbox 与账号创建领域事件 | 已采纳（追认） |
| [0007](0007-user-outbox-and-follow-events.md) | 用户模块自属 Outbox 与关注关系领域事件 | 已采纳（追认） |
| [0008](0008-video-counter-ownership-to-interaction.md) | 视频公开计数所有权划归互动服务 | 已采纳（追认） |
| [0009](0009-recommend-qdrant-vector-database.md) | 推荐服务引入 Qdrant 向量数据库与双模向量化引擎 | 已采纳（追认） |
| [0010](0010-following-recall-query-and-ranking.md) | 关注召回采用查询式两阶段召回与本地排序 | 已采纳（追认） |

## 待补

暂无。
