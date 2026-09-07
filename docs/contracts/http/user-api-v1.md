# User Service HTTP API v1

- 所有者：`user-service`
- 生效日期：2026-09-05
- 调用方：经 `gateway-service` 认证的新客户端和平台管理端
- 基础路径：`/api/users`

## 通用约定

所有接口必须携带 `Authorization: Bearer <token>`，不新增匿名白名单。网关清理客户端提交的
`X-User-*` 身份 Header 后注入已验证身份。成功响应沿用：

```json
{"code":200,"message":"ok","data":{}}
```

错误使用真实 HTTP 状态；响应 `code` 与 HTTP 状态一致。400 表示请求非法，401 表示缺少身份，
403 表示权限不足或本人资料停用，404 表示目标公开资料不可用，409 表示 revision 或管理状态冲突，
410 表示本人资料已逻辑删除。

## 本人资料

### `GET /api/users/me`

仅普通用户可用。物理资料缺失时返回 `profileState=PENDING`、`revision=0` 和空字段，不写数据库；
正常资料返回 `profileState=READY`。停用返回 403，已删除返回 410。

### `PATCH /api/users/me`

仅普通用户可用。请求必须包含非负 `revision` 和至少一个可修改字段：`nickname`、`bio`、`city`、
`birthday`。字段未出现表示不修改，显式 `null` 表示清空；首次保存缺失资料使用 revision 0。
成功编辑 revision 加一，版本冲突返回 409。`avatarUrl`、`gender`、`status`、`deleted` 不可通过本接口修改。

## 公开资料

### `GET /api/users/{accountId}`

已认证普通用户或管理员可调用。仅返回 `accountId`、`nickname`、可信 `avatarUrl`、`bio`；不存在、
停用和删除统一返回 404，不泄露生命周期差异。

### `POST /api/users/batch`

请求体：`{"accountIds":["<32位UUID>"]}`，数量 1～100。响应顺序和重复项与请求一致；不可用项为
`{"accountId":"...","available":false,"profile":null}`。摘要只含 accountId、nickname、可信头像。

## 管理接口

### `POST /api/users/admin/list?page=1&size=20`

仅管理员可调用，size 最大 100。查询条件仅支持 `accountId` 精确匹配、`nicknamePrefix` 和 `status`；
结果按 createdAt、accountId 稳定排序并排除逻辑删除资料。

### `PATCH /api/users/admin/{accountId}`

仅管理员可调用，字段和 revision 语义与本人 PATCH 相同。只能编辑已有 ACTIVE 资料，不创建资料、
不恢复删除资料、不启用停用资料。

## 兼容性

v1 当前无旧 User Service 契约调用方。新增可选响应字段通常兼容；收紧鉴权、修改字段类型或含义、
删除字段、改变错误语义均须发布新版本并记录迁移期。
