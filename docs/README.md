# 文档中心

本目录按“当前实现、接口契约、架构决策、当前待办、问题审计、方案归档”分工维护。不同类型文档的权威范围如下：

| 目录 / 文件 | 权威内容 | 使用方式 |
| :--- | :--- | :--- |
| [`api.md`](api.md) | HTTP 路径、请求响应、权限和错误码 | 对接客户端或服务间 HTTP 调用时优先查阅 |
| [`modules/`](modules/) | 各服务当前代码、表结构、消息和定时任务 | 判断系统现在如何工作 |
| [`adr/`](adr/) | 架构决策、边界、取舍和长期约束 | 判断为什么这样设计 |
| [`TODO.md`](TODO.md) | 当前未完成事项、验收项和后续规划 | 判断下一步做什么 |
| [`audits/`](audits/) | 问题证据、历史修复记录和剩余风险 | 追溯已经发现过什么问题 |
| [`plans/`](plans/) | 实施方案历史归档 | 追溯方案过程，不作为当前状态来源 |

## 查阅顺序

1. 想了解当前实现：先看对应的 [`modules/`](modules/) 文档。
2. 想确认 HTTP 契约：查看 [`api.md`](api.md)。
3. 想理解架构取舍：查看 [`adr/README.md`](adr/README.md) 和对应 ADR。
4. 想查看未完成事项：查看 [`TODO.md`](TODO.md)。
5. 想追溯问题和修复：查看 [`audits/`](audits/)。
6. 想查看历史实施过程：查看 [`plans/`](plans/)，但不要用它覆盖当前模块文档或 TODO 的状态。

## 服务模块

| 模块 | 当前实现文档 | 当前审计记录 |
| :--- | :--- | :--- |
| gateway | [`modules/gateway.md`](modules/gateway.md) | 暂无独立审计清单 |
| auth | [`modules/auth.md`](modules/auth.md) | 暂无独立审计清单 |
| user | [`modules/user.md`](modules/user.md) | 暂无独立审计清单 |
| content | [`modules/content.md`](modules/content.md) | 暂无独立审计清单 |
| audit | [`modules/audit.md`](modules/audit.md) | 暂无独立审计清单 |
| interaction | [`modules/interaction.md`](modules/interaction.md) | [`audits/interaction-audit.md`](audits/interaction-audit.md) |
| recommend | [`modules/recommend.md`](modules/recommend.md) | [`audits/recommend-audit.md`](audits/recommend-audit.md) |
| file | [`modules/file.md`](modules/file.md) | 暂无独立审计清单 |
| transcode | [`modules/transcode.md`](modules/transcode.md) | 暂无独立审计清单 |
| common | [`modules/common.md`](modules/common.md) | 暂无独立审计清单 |

## 维护规则

- 当前实现变化先更新 `modules/` 或 `api.md`，再按需要补充 ADR、TODO 和审计记录。
- `TODO.md` 只保留未完成事项；已完成内容只保留索引，不复制完整实现说明。
- `audits/` 记录问题的证据、影响、修复提交和剩余风险，不替代 TODO 看板。
- `plans/` 保留历史方案，不作为当前实现、验收或兼容策略的依据。
- 文件名、目录名和链接变更后，必须同步检查全仓 Markdown 链接。
