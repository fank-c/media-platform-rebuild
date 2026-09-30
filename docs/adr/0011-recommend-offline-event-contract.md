# 0011 推荐下架事件采用内容服务当前契约

## 状态

已采纳。

## 背景

content-service 主动下架使用 `content.video.offline` 作为事件类型与路由键，推荐侧误绑定 `content.video.offlined`，导致消息无法进入候选池生命周期队列。当前工程未上线，没有旧消费者兼容要求。

## 决策

- 推荐侧统一采用 `content.video.offline`，同步常量、绑定、消息模型注释、消费用例和测试。
- 沿用 `media.platform.events` 交换机和 `recommend-service.video-lifecycle.v1` 队列，不增加版本或双路由。
- 下架消费将自属候选状态置为 `OFFLINE`；重复消费保持该状态。
- 内容服务发布契约不变，不新增跨服务查询，不改网关鉴权。

## 备选方案

- 推荐侧同时绑定两个路由：拒绝。当前无已发布兼容要求，会保留无意义的旧路径。
- 修改内容服务为 `offlined`：拒绝。现有生产端和契约已经统一为 `offline`，应修正错误订阅端。

## 后果

- 内容服务下架事件可按声明的绑定进入推荐队列，消费后候选不再参与实时推荐。
- 这不解决已有 Redis 缓冲的过期物料，出队状态复核仍属于 REC-02。
- 本次自动测试覆盖绑定对象、真实消费用例和重复消费状态；真实 RabbitMQ 送达及数据库联调仍需独立验证。

## 关联

- [推荐模块](../modules/recommend.md)
- [内容模块](../modules/content.md)
- [审计记录](../audits/recommend-audit.md)
