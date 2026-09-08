# 文件 HTTP API v1

- 状态：已实施，待独立验收；真实环境直传尚未获准启用。
- 生产者：file-service；调用方：经网关认证的新客户端。当前没有 content、RAG 或 user-service 的跨服务读取调用方。
- 认证：业务 API 仅接受网关已验证后下传的普通用户主体；缺身份为 401，非普通用户为 403。他人、未知及已逻辑删除 ID 统一为 404。

## 统一响应与兼容性

成功响应使用 `ApiResponse<T>`，其 HTTP 状态是真实语义的一部分，不能只在 body 的 `code` 中表达。时间为 UTC ISO-8601，ID 为字符串。`storageKey`、bucket、内部端点和 SDK 错误不对外暴露。现有 v1 不承诺创建请求级幂等：客户端创建超时重试可能得到新 `fileId`；确认和删除按 `fileId` 幂等。

`declaredSize` 是客户端声明；`actualSize` 仅在 `COMPLETED` 时非空，且届时保证等于 `declaredSize`。`sha256` 为服务端某次读取时的 SHA-256 观测结果，不代表后续对象永不被覆盖。

## 接口

| 方法和路径 | 输入 | 成功响应 | 主要失败 |
| --- | --- | --- | --- |
| `POST /api/files` | multipart 字段 `file`，可选受控 `storageType` | `201` + 已完成 Metadata | `400` 空文件/名称，`413` 超限，`503` 存储或元数据结果未确认 |
| `POST /api/files/direct-upload` | JSON：`originName`、正整数 `size`，可选 `mime`、`storageType` | `201` + `fileId`、`PENDING`、`putUrl`、`requiredHeaders`、两个到期时间 | `400` 输入，`413` 超限，`503` 签名失败（PENDING 由清理收敛） |
| `POST /api/files/{id}/confirm` | 无摘要、key、size 覆盖参数 | 已完成 `200` + Metadata；新接收或在途 `202` + PENDING | `410` 过期，`503` 队列拒绝/临时故障，拒绝时有 `Retry-After: 2` |
| `GET /api/files/{id}` | 无 | `200` + Metadata；PENDING 和 EXPIRED 都可查询 | `404` 未知/他人/墓碑 |
| `GET /api/files/{id}/download-url` | 无 | `200` + 短期 `url`、`expiresAt`，且 `Cache-Control: no-store` | `409` PENDING 或对象状态异常，`410` EXPIRED，`503` 存储故障 |
| `DELETE /api/files/{id}` | 无 | `204`；本人已存在墓碑仍为 `204` | `404` 未知/他人，`503` 远端删除结果未知或墓碑未确认 |

预签名 URL 是短期持有者凭据，持有人可在对象传输例外中绕过网关读取；客户端不得记录、分享或长期缓存 URL。`requiredHeaders` 必须按返回值原样发送，不能自行替换 URL host、有效期、bucket 或对象 key。

## 版本与限制

新增字段必须保持可选和向后兼容；删除/重命名字段、收紧认证或改变错误语义时发布 v2，并记录调用方迁移期。本期不支持公开文件、管理员越权、跨服务内容读取、业务引用、分片续传、秒传或领域事件。完整性、删除竞争和真实对象入口门槛见 [文件服务问题清单](../../reference/file-service-known-issues.md)。
