# 用户模块 · user-service

用户模块维护以认证账号 ID 为标识的用户资料，包括昵称、简介、城市、生日及展示信息。账号、密码和认证角色由认证模块管理，本模块不修改这些数据。注册后的资料通过消息异步创建，也允许普通用户首次保存时建档；因此“注册成功”与“资料已就绪”不是同一个时刻。

所有 HTTP 接口通过网关访问并携带访问令牌。“公开资料”是指向其他已登录用户展示的摘要，不代表匿名接口；本人编辑只允许普通用户，管理员使用管理端接口。

## Part 1：用户资料初始化

### 接收账号创建通知

用户服务监听 `user-service.auth-account-created.v1` 队列，接收认证服务发出的账号创建事件，不要求客户端再次调用初始化接口。消费者开关 `user.messaging.account-created.enabled` 在仓库中默认开启。

处理顺序如下：

1. 解码事件并检查类型 `auth.account.created`、版本 `1`、合法事件 ID，以及载荷账号 ID 与事件主体是否一致。只接收 `accountType=user`；未知附加字段允许保留兼容。
2. 按事件 ID 写消费记录，重复事件直接返回重复处理结果。
3. 按账号 ID 检查物理资料记录，包括已经删除的记录。真正缺失时创建默认正常资料，初始 `revision=0`；已有资料保持原样。
4. 在同一事务中保存消费结果和资料。数据库处理失败时一起回滚，不能留下“已消费但没建档”的成功记录。

默认建档不生成昵称、头像等个性信息。停用和已删除资料不会因旧事件重投被恢复；同一账号收到不同事件 ID 时，也不会覆盖已有资料。

非法事件会被转换为拒绝且不重新入队的异常；监听容器另开启有限重试，默认最多 3 次尝试。不能仅凭消费者抛出拒绝异常就声称真实容器只调用一次，重试和拒绝的组合仍需联调。最终拒绝的消息通过死信交换机进入 `user-service.auth-account-created.v1.dlq`。死信保留失败消息，不表示系统会自动修复并重放；当前没有对外重放接口。

源码入口：[事件解码](../../service/user-service/src/main/java/com/calles/platform/user/interfaces/messaging/AccountCreatedEventDecoder.java)、[消费事务](../../service/user-service/src/main/java/com/calles/platform/user/application/event/AccountCreatedEventProcessor.java)、[资料表](../../service/user-service/db/schema/user-profile.sql)。生产侧流程见[认证模块 Part 4](auth.md#part-4账号创建通知)。

## Part 2：本人资料查询与编辑

### 查看资料是否就绪

普通用户调用 `GET /api/users/me`。用户 ID 来自网关注入的身份，而不是由请求参数指定。没有资料时，响应 `data.profileState=PENDING`、`revision=0`，资料字段为空；GET 不写库。调用方可以稍后重查，也可以进入首次资料保存。

正常资料返回 `profileState=READY` 和当前版本。本人资料已停用时返回 `403`，已删除时返回 `410`，不是返回等待状态，也不会自动重建。管理员不能借本人接口自动创建普通用户资料。

### 首次保存和局部修改

调用 `PATCH /api/users/me`，提交当前 `revision` 以及至少一个可编辑字段。例如清空简介并修改昵称：

```json
{
  "revision": 0,
  "nickname": "示例昵称",
  "bio": null
}
```

`revision` 应取最近一次查询或保存返回的值，只有尚未建档的首次保存通常从 `0` 开始。可编辑字段为 `nickname`、`bio`、`city`、`birthday`：字段不出现表示不改，显式 `null` 表示清空。昵称非空时不能是纯空白，文本会去首尾空白；昵称、简介、城市长度上限分别为 64、500、100，生日不能晚于当天。

服务先校验输入，再检查资料。资料缺失时先创建默认记录；若与消息初始化竞争，则重读实际记录，不能覆盖停用或已删除资料。之后只更新本次提交的字段，数据库条件同时要求版本匹配、资料正常且未删除；成功后版本加一，返回更新后的本人资料。

例如两个页面都读到版本 `2`，第一个保存后变成 `3`，第二个仍提交 `2` 就收到 `409`。调用方应重新获取资料并让用户确认修改，而不是盲目重复提交。初始化与修改处于同一事务，首次保存失败也不能算资料完善成功。

头像和性别不是当前 PATCH 可编辑字段；不要从查询响应包含这些字段推断存在修改接口。

源码入口：[用户 HTTP 接口](../../service/user-service/src/main/java/com/calles/platform/user/interfaces/http/UserProfileController.java)、[资料用例](../../service/user-service/src/main/java/com/calles/platform/user/application/profile/UserProfileApplicationService.java)、[带版本的更新条件](../../service/user-service/src/main/java/com/calles/platform/user/infrastructure/persistence/UserProfileMapper.java)。

## Part 3：公开资料展示

### 单个和批量查询

已登录主体调用 `GET /api/users/{accountId}`，取得账号 ID、昵称、允许展示的头像和简介；不返回生日等本人视图数据。账号 ID 必须是 32 位十六进制。对于不存在、停用或已删除的资料，统一返回 `404`，不暴露不可用原因。

列表页可以调用 `POST /api/users/batch`，JSON 提交 `accountIds` 数组，数量为 1–100。服务一次读取正常资料，再按原请求顺序组装每项 `accountId`、`available` 和 `profile`。重复 ID 保留重复位置；不可用项为 `available=false`、`profile=null`，不会因一个缺失账号丢掉整批其余结果。批量摘要只有账号 ID、昵称和头像，不包含单条查询中的简介。

### 头像只负责展示过滤

已有头像地址经过 `AvatarDisplayPolicy` 的可信前缀过滤，不符合条件就隐藏。`user.profile.avatar-allowed-prefix` 默认空，因此默认不展示历史头像地址。这里没有文件上传、所有权验证或头像绑定链路，不能把前缀过滤当作完整头像管理能力。

源码入口：[头像展示策略](../../service/user-service/src/main/java/com/calles/platform/user/application/profile/AvatarDisplayPolicy.java)、[默认配置](../../service/user-service/src/main/resources/application.yml)。

## Part 4：管理端资料维护

### 查找与编辑已有资料

管理员调用 `POST /api/users/admin/list?page=1&size=20`。可选 JSON 条件为 `accountId`、`nicknamePrefix`、`status`；其中状态为 `ACTIVE` 或 `DISABLED`，分页大小为 1–100。服务排除逻辑删除记录，按创建时间和账号 ID 排序，返回记录、总数及分页信息。

修改使用 `PATCH /api/users/admin/{accountId}`，输入规则与本人 PATCH 相同，必须提交当前 `revision`。管理员只能编辑已有正常资料：缺失或删除返回 `404`，停用资料返回 `409`，版本冲突也返回 `409`。管理接口不允许顺带建档、恢复、启停或修改认证角色；普通用户调用管理接口返回 `403`。

管理修改记录操作者、目标账号、提交字段名和结果，不把完整资料前后值写入这条审计日志。权限入口见[用户访问策略](../../service/user-service/src/main/java/com/calles/platform/user/application/security/UserAccessPolicy.java)。

## 验证方式与当前结果

应验证消息重投与事务回滚、初始化和首次保存竞争、GET 不写库、字段缺省与显式清空、版本冲突、公开信息最小化、普通用户与管理员的权限区分，以及停用和删除资料不被恢复。

现有[用户测试目录](../../service/user-service/src/test/java/com/calles/platform/user)包含资料用例、事件处理、事件解码、消费者和配置测试。资料用例的模拟数据库返回、消费处理的替身断言不等于真实 SQL 事务或消息死信链已验证。

本次（2026-09-09）实际完成接口、权限策略、资料更新 SQL、消息配置及相关测试断言的静态核对，以及文档链接与格式检查。未执行 Maven 编译或测试，未启动服务，未执行 MySQL、RabbitMQ 或网关链路联调。
