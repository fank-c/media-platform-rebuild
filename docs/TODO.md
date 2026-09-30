# 当前待办与验收清单

本文件只记录当前未完成事项、验收项和后续规划。已完成能力不在这里展开实现细节，详细内容分别见对应的模块文档、API 契约和 ADR。

状态含义：`待处理` 表示需要实现；`待决策` 表示规则或范围尚未确认；`待验收` 表示代码已落地但验证尚未完成；`规划` 表示尚未进入当前实现范围。

## 模块待办

### audit-service

- [ ] 敏感词字典动态增删查接口。
- [ ] 对接阿里云内容安全等真实第三方云机审 SDK 适配器。

### transcode-service

- [ ] HLS（`.m3u8` + `.ts`）分片转码（阶段二规划）。

## interaction-service

说明：当前实现见 [`modules/interaction.md`](modules/interaction.md)，历史问题见 [`audits/interaction-audit.md`](audits/interaction-audit.md)。

### 观看链路验收

- [ ] 开启 `WATCH_HEARTBEAT_IT_ENABLED=true`，验证元数据事件幂等、事件凭据唯一键防重和重复序号零写入。

### 时间源统一

- [ ] 完成互动模块完整回归、本地 MySQL 集成及 UTC/非 UTC 默认时区验证。

### 计数与 Outbox 验收

- [ ] 完成真实 MySQL 多实例汇总、业务回滚和增量清理保留期集成验证，并观察积压量与批处理延迟。
- [ ] 完成 Outbox 派发与清理真实环境验收：验证 RabbitMQ 到推荐消费链路、MySQL 清理并发和指标告警。

### 游客访问策略

- [ ] 确认 `my-state` 与 `watch-progress` 是否经网关向游客开放；当前网关要求登录，服务内部匿名回退返回零值（INT-09）。

### 后续规划

- [ ] 树形评论与楼中楼，接入机审与多级排序。

## recommend-service

说明：当前实现见 [`modules/recommend.md`](modules/recommend.md)，问题审计见 [`audits/recommend-audit.md`](audits/recommend-audit.md)。

### P1 问题

- [ ] **REC-01 验收**：代码已统一 `content.video.offline`，绑定、消费与重复消费测试通过；待真实 RabbitMQ 送达与 MySQL 状态落库联调。
- [ ] **REC-05**：消费 `content.video.unbanned`，在满足发布准入时恢复候选为 `ACTIVE`。
- [ ] **REC-02**：Redis 待看缓冲出队时复核候选状态和用户屏蔽。
- [ ] **REC-04**：确定客户端播放时长作为弱信号的范围、权重和异常规则。
- [ ] **REC-06**：为推荐消费补充有界重试、死信和告警出口。

### P2 问题与规划

- [ ] **REC-07**：明确热度召回使用互动公开计数、推荐反馈，还是组合口径。
- [ ] **REC-08**：实现相关推荐接口 `GET /api/recommend/videos/{vid}/related`。

## 已完成能力索引

以下内容已经落地，不在本清单重复展开：

- 认证、用户、文件、网关、内容、审核和转码模块的当前能力，见对应 [`modules/`](modules/) 文档。
- 互动点赞、收藏、观看、分享、计数增量、游客只读和 Outbox 基础链路，见 [`modules/interaction.md`](modules/interaction.md)。
- 推荐向量化、候选池、首页推荐流、互动事件消费和用户反馈，见 [`modules/recommend.md`](modules/recommend.md)。
- 推荐 REC-03 参数 `400`、缺少身份 `401` 与统一 HTTP 错误响应，见 [`audits/recommend-audit.md`](audits/recommend-audit.md)。
- 观看拆表、计数增量和事件 Outbox 的架构取舍，见 [`adr/`](adr/) 对应记录。

历史问题和修复证据见 [`audits/`](audits/)，历史实施方案见 [`plans/`](plans/)；两者都不是当前待办的替代品。
