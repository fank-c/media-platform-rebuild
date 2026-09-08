# 数据与存储参考

本页记录当前 SQL 定义与所有权，不复制整套 DDL。建表操作见
[数据库初始化](../database-setup-guide.md)，迁移门槛见 [迁移计划](../migration-plan.md)。

## 当前 Schema 与源码入口

- [空库 Schema 快照](../../db/init/schema.sql)：一次性创建当前全部五张业务表。
- auth-service 按表定义：[认证账户](../../service/auth-service/db/schema/auth-account.sql)、[可靠事件 Outbox](../../service/auth-service/db/schema/auth-outbox.sql)、[资料补齐进度](../../service/auth-service/db/schema/auth-profile-backfill-progress.sql)。
- user-service 按表定义：[用户资料](../../service/user-service/db/schema/user-profile.sql)、[消费幂等登记](../../service/user-service/db/schema/user-consumed-event.sql)。
- 当前仍处于空库测试阶段，不保留针对既有表的 `ALTER TABLE` 过渡脚本；未来已建库环境发生结构演进时，必须单独提供前向兼容迁移、回填和回退方案。


脚本存在不代表目标数据库已执行，也不代表领域迁移完成。工程未配置自动迁移工具或 Compose SQL 挂载。

| 表 | 所有者 | 关键字段与约束 |
| --- | --- | --- |
| `auth_account` | auth-service | `id CHAR(32)`，ASSIGN_UUID；`login_name VARCHAR(255)` 唯一；`password_hash` 仅 BCrypt；认证状态 `ACTIVE/DISABLED`；`deleted` 由 MyBatis-Plus 逻辑删除维护 |
| `auth_outbox` | auth-service | 注册事务内待发布的版本化领域事件；按状态、下次重试时间和租约索引投递；失败消息保留供有界重试 |
| `auth_profile_backfill_progress` | auth-service | 以账户、事件类型和版本为主键，确保历史普通账号资料补齐事件只生成一次 |
| `user_profile` | user-service | `account_id CHAR(32)` 主键，逻辑关联认证 ID；昵称、头像、简介、城市、性别、生日可空；资料状态 `ACTIVE/DISABLED`；`deleted` 由 MyBatis-Plus 逻辑删除维护；不创建跨服务外键 |
| `user_consumed_event` | user-service | 以消费者名称和事件 ID 为联合主键，保证账号创建事件可幂等消费 |

两表均有毫秒精度创建/更新时间。`gender` 尚无已确认枚举语义；不得从字段类型推断业务取值。
注册仅写认证账户；资料表的存在不表示注册后自动生成资料。
`auth_account.status` 决定能否登录、刷新和签发凭据；`user_profile.status` 仅表示资料在用户域内是否可用，
不得被 user-service 用来替代认证授权判断。两表的 `deleted=1` 会被其所属服务的 MyBatis-Plus Mapper 自动过滤；
逻辑删除不等于注销，账户会话吊销仍由 auth-service 单独处理。
登录名比较受数据库排序规则影响；当前脚本 `utf8mb4_unicode_ci` 不支持“大小写敏感”承诺。

## 服务所有权

第一阶段同一 MySQL 实例、同一逻辑库；前缀是架构访问约束，不等于数据库权限已经逐表隔离。
认证、用户、内容、审核、互动、推荐分别使用 `auth_`、`user_`、`content_`、`audit_`、
`interaction_`、`recommend_`。当前后四类不应被列为已有业务表。

禁止跨服务 Mapper、SQL Join、触发器或共享实体；跨域事实通过版本化 API 或事件获取。
用户资料的 `account_id` 必须复用认证主体 ID，不能生成无关联的新身份。

## 当前 Redis 所有权

| Key 模式 | 所有者 | 含义与寿命 |
| --- | --- | --- |
| `auth:session:<sid>` | auth-service | 刷新会话 Hash，含账户、角色、刷新摘要和 ISO `expiresAt`；轮换中临时增加 `rotationId` 与 `rotationState=inflight`，所有字段与 Refresh 会话使用同一绝对过期时刻 |
| `auth:refresh:<sha256>` | auth-service | 刷新凭据哈希到 sid 的 string 索引；begin 原子删除旧索引，finish 仅以 `SET NX PXAT` 建立新索引，不能覆盖已有索引 |
| `auth:revoked:jti:<jti>` | auth-service | 当前访问令牌撤销标记，保留至令牌自然到期 |
| `gateway:auth:<sha256>` | gateway-service | 验证结果缓存；有效结果 TTL 不超过配置值和令牌剩余寿命 |

旧会话缺失两个轮换字段时兼容为 ready；仅有一个字段、空 nonce、未知状态、错误键类型或无 TTL 都拒绝刷新。
进程崩溃留下 in-flight 时不恢复旧索引、不接管，会话随原 TTL 过期。详见 [认证设计](authentication.md)。
禁止存储或在排障记录中打印完整凭据。
Redis 不是账户角色和状态的唯一事实来源。

## 对象与后续演进

MinIO 存对象本体；文件元数据和授权归 `content-service`，相关业务表和事件仍待具体功能点设计。

结构变更同时更新服务私有 SQL 与空库快照，声明兼容、回填、核验和回退限制。
`CREATE TABLE IF NOT EXISTS` 只会跳过已有表，不会把旧列自动升级为最新结构。
大批量回填需分批、重试、进度和集合校验；旧表删除、共享库修改、双写切换都需明确确认。


## file-service 文件元数据

`file_asset` 仅由 file-service 读写，DDL 源为 `service/file-service/db/schema/file-asset.sql`，空库快照同步在 `db/init/schema.sql`。`declared_size` 是客户端声明，`size` 和 `sha256` 只在 `COMPLETED` 后由服务端流式读取填充；新文件的 `storage_key` 由服务端 UTC 创建日期与文件 ID 组成，例如 `assets/2026/09/08/<id>`，一经写入即作为后续操作的定位依据；`storage_key`、bucket、端点均不属于对外 DTO。

首期只允许 `MINIO`，对象 key 永不复用；PENDING 只能条件转为 COMPLETED 或 EXPIRED。既有环境必须在执行前后用 `SHOW CREATE TABLE file_asset` 与 DDL 对照；发现列、约束或索引漂移即停止，不隐式 ALTER。未进行旧单体回填，也不设置跨服务外键。
