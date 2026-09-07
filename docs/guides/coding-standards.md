# 编码规范

本页将原注释报告与通用编码建议收敛为本工程适用的检查清单；完整授权规则见
[AGENTS.md](../../AGENTS.md)，本页不替代或放宽它。

## 语言与风格

- 项目沟通、业务提示及新增实质修改单元的注释默认中文；标识符和协议字段保持英文。
- Java 目标 17，保持周围代码的缩进、导入和命名风格，不顺手全仓格式化。
- 类名用 PascalCase，方法和字段用 camelCase，常量用 UPPER_SNAKE_CASE。
- 当前 POM 未接入 Spotless，不提供 `spotless:apply` 作为既有工作流；工具引入需独立说明用途。

## 分层与依赖

Controller 只处理 HTTP 校验、映射和响应；Application/Service 编排用例；Repository/Mapper
访问服务拥有的数据。参考认证服务现有 `interfaces/http`、`application`、`domain`、
`infrastructure` 分层，不要求所有骨架一次性重构成相同目录。

`common-core` 不放领域规则；`common-web` 不放业务实现。禁止跨服务 Mapper、共享实体、
跨域 SQL Join、在 Controller 中编排远程业务以及在本地事务内调用远程服务或发布消息。

## Auth/User 本地目录约定

本轮只收敛 auth/user 已实现的技术代码，不把该目录形态强加给尚未迁移的服务：

- `infrastructure/security` 放密码散列、JWT 编解码和 Redis 会话等认证技术实现；`application` 只编排注册、登录、刷新和注销用例。
- `infrastructure/messaging` 放本服务产生的事件 Payload 与 JSON 工厂；`infrastructure/outbox` 放本地可靠投递记录、状态、Mapper、仓储和发布器。
- `infrastructure/persistence` 放普通 MyBatis Mapper 与其查询快照；启动类的 `@MapperScan` 必须限定本服务目录并使用 `@Mapper` 过滤。
- 消费服务可以让 Application 直接接收 `EventEnvelope<本地Payload>`；JSON、AMQP、MDC 和旧输入兼容只留在 `interfaces/messaging`，不要再新增没有业务含义的投影包装。

## 中文注释要求

| 单元 | 注释必须解释的内容 |
| --- | --- |
| 类、接口、枚举、Record | 职责、边界、协作对象及不承担的工作 |
| 字段、Record 属性 | 业务含义、单位、取值、生命周期、默认值、可空性或敏感性 |
| 构造器、公共和私有方法 | 用途、参数、返回、异常及适用的权限、幂等性和副作用 |
| 关键过程 | 在代码块前说明校验、权限、事务、持久化、缓存、消息、外部调用与清理的原因和失败出口 |
| 配置和脚本 | 分组、变量关联、默认值原因和过渡策略 |

不要只注释公共 API，也不要逐句翻译代码或保留错误旧注释。简单 getter/setter 可以短注释，
复杂逻辑先改善结构，再解释关键约束。禁止作者、日期等无业务价值模板。

## 数据、异常与安全

- 表结构修改提交服务私有 SQL，同步空库快照；不依赖启动自动建表。
- 唯一性最终由数据库约束保障；应用预检查不能替代并发测试。
- HTTP 状态与响应码须匹配既有契约。内部异常不得泄漏 SQL、密码、Token 或个人信息。
- 密码只保存适当哈希；本工程认证使用 BCrypt。JWT、刷新凭据、签名密钥不得写入日志。
- 事务范围尽量小；缓存不能替代账户状态等业务事实；重试只用于幂等且有次数上限的操作。
- ThreadLocal 请求上下文必须清理，异步线程和消息消费者不能假定自动继承身份。

## 提交前检查

确认职责未越界、契约已同步、中文注释覆盖实际修改单元、测试涵盖失败路径、配置没有真实凭据。
验证方式见 [测试指南](testing.md)，涉及新的公共模块、同步调用或核心架构决策时新增 ADR。
