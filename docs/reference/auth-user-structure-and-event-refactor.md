# Auth/User 目录收敛与事件类型化修复方案

- 原方案状态：**待独立审查，未授权实施**（形成时的历史状态）。
- 执行状态：2026-09-06 用户已明确授权实施；已完成 auth/user 局部目录收敛、领域模型按业务对象归组、类型化消费及 HTTP/消息入口扁平化。最新模块 `verify` 结果以测试指南为准；仍待未参与实施者独立验收和隔离集成验收。
- 核对日期：2026-09-06；事实来源是当前未提交工作区，不是 Git HEAD 或已部署服务。
- 形成方式：独立代理提出方案骨架，主任务核对源码、契约和本地依赖后整理；主任务进一步简化了消费输入，并提出无生产 `JsonNode` 的局部版本适配。**这不是独立审查通过结论。**
- 范围：`auth-service`、`user-service` 的目录组织与 `auth.account.created.v1` 的类型表达。

## 1. 推荐方向与完成后的阅读方式

**保留可靠投递和业务行为，减少目录分散及机械对象转换；不建设新的消息框架。**

本轮不把整个工程换成另一套目录哲学：保留已有 `interfaces`（协议入口）、`application`（业务用例）、
`domain`（业务模型）、`infrastructure`（技术实现）及 `config`。优先修复当前最分散的 Outbox，
并统一 auth/user 的数据库访问目录。HTTP Controller 已位于 `interfaces/http`，不重复搬迁。

修复后阅读注册链路只需沿两条线：

```text
AuthService.register
  → AccountCreatedEventFactory：用本地 Payload 和公共 EventEnvelope 生成 JSON
  → AuthOutboxRepository：与账号一起入库
  → AuthOutboxPublisher：事务外发送，更新投递结果

AccountCreatedConsumer
  → AccountCreatedEventDecoder：JSON 一次绑定为明确的事件类型并校验
  → AccountCreatedEventProcessor：消费登记与资料初始化同事务
```

`EventEnvelope<T>` 是已有公共消息外壳，保存事件 ID、版本和业务载荷；两端各自定义
`AccountCreatedPayloadV1`。不共享数据库实体，不增加服务间编译依赖，也不为“解耦”回避明确类型。

### 范围与成本账

| 项目 | 本轮决定 |
| --- | --- |
| 新 Maven 模块、基础设施、依赖、表、迁移脚本、配置键、事件版本 | 均为 **0** |
| 业务变化 | **0**；API、JWT、权限、资料生命周期和投递保证保持 |
| 生产 Java 文件 | 当前 auth 39、user 27；auth 仅移动/修改，user 新增 1 个载荷、移除 2 个包装，预计合计 65 个 |
| 消费转换 | 删除 `DecodedAccountCreatedEvent`、`AccountCreatedEventInput`，不再投影一次输入 |
| 必要的兼容成本 | Decoder 内固定的局部 Jackson 配置及字段适配；必须单独测试，不以嵌套类掩饰工作量 |
| 存量补齐 | 保留能力、进度表、默认关闭/dry-run 及已有调度语义，仅按映射移动代码 |
| 明确不做 | 不删幂等表、不重做状态机、不改同步/异步策略、不升级依赖、不顺带修认证缺口 |

数量仅用于防止膨胀，不是质量指标；现有职责不同的 Outbox 记录不能为了减少文件强行合并。
新增任何顶层生产类、配置或额外转换层，须先说明必要性并回到方案审查。

## 2. 已核实基线与必须保持的约束

1. auth 的 Controller 已在 `interfaces/http`；启动类保留在服务根包，负责组件扫描入口。
2. auth 事件类型和工厂在 `application/outbox/event`，Outbox 状态、Mapper、记录、仓储与发布器分散。
3. user 当前链路为 `Consumer → Decoder(JsonNode) → DecodedAccountCreatedEvent → Input → Processor`。
4. 注册在 `AuthService.register` 的本地事务中同时写 `auth_account` 和 `auth_outbox`。
5. user 在 Processor 的事务中写 `user_consumed_event` 并初始化物理缺失资料；重复事件不得重复改变资料。
6. 发布器已有领取租约、claim token、确认、退避、最大尝试次数及积压指标，本轮不重写这些机制。
7. 当前有 16 个测试源码文件（auth 10、user 6）；这是源码盘点，不代表本轮测试通过。
8. 两服务 `@MapperScan` 当前只指向各自的 `infrastructure.mapper`，迁包必须同步修改扫描。

必须保持的行为以 [事件契约](../contracts/events/auth.account.created.v1.md)、
[认证 HTTP 契约](../contracts/http/auth-api-v1.md)、[用户 HTTP 契约](../contracts/http/user-api-v1.md) 为准。
当前源码存在的已知问题不能混入本轮修复；基线测试失败须记录并先分类，不能静默改契约使测试通过。

## 3. 目录目标与逐组迁移映射

下表路径相对于各服务的 `src/main/java/com/calles/platform/auth/` 或 `user/`。
源码迁移同时修改 `package`、导入和相应测试包；除表列变化外，其余位置不变。

### 3.1 auth

| 当前位置/类 | 目标位置 | 原因 |
| --- | --- | --- |
| `AuthApplication` | 原位 | 保留扫描根，仅调整 Mapper 扫描声明及必要中文注释 |
| `interfaces/http/**`、`exception/**`、`config/**`（除 `AuthExceptionHandler`） | 原位 | Controller、HTTP DTO、业务异常和配置已经有清晰入口 |
| `application/AuthService` | 原位 | 保留注册、登录等用例和事务边界 |
| `application/PasswordService`、`TokenService`、`SessionService` | `infrastructure/security/` | 密码算法、JWT 编解码、Redis 会话属于认证技术实现；不另加接口或包装 Service |
| `application/profilebackfill/ProfileBackfillService` | `application/ProfileBackfillService` | 保留补齐用例，不为单类保留额外子层 |
| `application/outbox/event/AccountCreatedEventFactory`、`AccountCreatedPayloadV1` | `infrastructure/messaging/` | 账号创建消息的类型与序列化放在一起，不以 Outbox 命名业务事件 |
| `application/outbox/event/AuthOutboxRecord` | `infrastructure/outbox/` | 这是待入库记录，不是业务事件 |
| `domain/model/AuthOutboxStatus` | `infrastructure/outbox/` | 投递状态属于技术机制，不与账户状态混放 |
| `infrastructure/outbox/persistence/` 下 Repository、ClaimedOutboxMessage、OutboxBacklogSnapshot | `infrastructure/outbox/` | 与发布器和 Outbox SQL 就近阅读 |
| `interfaces/messaging/user/AuthOutboxPublisher` | `infrastructure/outbox/` | 发布器与记录、仓储、领取状态和 SQL 相邻，排查可靠投递不再跳转到入站接口目录 |
| `infrastructure/mapper/AuthOutboxMapper` | `infrastructure/outbox/` | Outbox 的领取/状态 SQL 与其编排集中 |
| `config/AuthExceptionHandler` | `interfaces/http/` | HTTP 异常到响应的映射属于 HTTP 入口边界，与 Controller 相邻阅读 |
| `infrastructure/mapper/` 下 AuthAccountMapper、ProfileBackfillMapper、ProfileBackfillCandidate | `infrastructure/persistence/` | 普通持久化入口统一，查询结果不再混入名为 mapper 的接口目录 |
| `domain/model/AuthAccount`、`domain/enums/AccountRole`、`AccountStatus` | `domain/account/` | 同一账户的实体、角色和状态集中，阅读认证账户不再在模型与枚举目录间跳转 |
| `infrastructure/scheduling/**`、`infrastructure/observability/**` | 原位 | 保留补齐调度与指标职责，不扩展公共设施 |

Outbox 中保留三个数据记录：`AuthOutboxRecord` 用于首次入库，`ClaimedOutboxMessage` 携带领取令牌和尝试次数，
`OutboxBacklogSnapshot` 表达聚合指标。它们不是同一份数据的层层投影，不合并。

### 3.2 user

| 当前位置/类 | 目标位置/动作 | 原因 |
| --- | --- | --- |
| `UserApplication` | 原位 | 更新 Mapper 扫描，补充被修改代码单元的中文职责说明 |
| `infrastructure/mapper/UserProfileMapper`、`UserConsumedEventMapper` | `infrastructure/persistence/` | 与 auth 使用相同持久化目录规则 |
| `interfaces/messaging/auth/AccountCreatedConsumer`、Decoder | `interfaces/messaging/` | 当前只有账号创建消息入口，类名已表达来源领域，不为单组接收代码保留额外层级 |
| `exception/InvalidAccountCreatedEventException` | 原位 | 协议拒绝仍是服务内部异常分类，不将其移动到入站组件中 |
| `interfaces/messaging/auth/DecodedAccountCreatedEvent` | 删除 | Decoder 直接返回包含 traceId 的类型化信封，不再包一层 |
| `application/event/AccountCreatedEventInput` | 删除 | Processor 直接消费无框架的本地事件类型，不保留机械字段投影 |
| 新 `application/event/AccountCreatedPayloadV1` | 新增 | user 自己拥有的纯 Java 载荷；与 auth 的同版本载荷字段一致 |
| `application/event/AccountCreatedEventProcessor` | 原位，更新参数类型 | 保留消费幂等与资料初始化事务，不并入监听器 |
| `interfaces/http/profile/UserProfileController` | `interfaces/http/` | 当前仅有一组资料 HTTP 入口，去掉只包容该组代码的额外目录 |
| `interfaces/http/profile/dto/**` | `interfaces/http/dto/` | 与扁平后的资料入口相邻，保留 DTO 层而不增加功能分组 |
| `config/UserProfileExceptionHandler` | `interfaces/http/` | HTTP 异常到响应的映射与资料入口同属接口边界 |
| `domain/model/UserProfile`、`domain/enums/ProfileStatus` | `domain/profile/` | 同一资料实体及其生命周期状态集中，阅读资料规则不再跨模型与枚举目录 |
| 其余 `application`、其余 `config`、`exception`、`infrastructure/observability` | 原位 | 不扩大为整个用户领域重构 |

Processor 接收 `EventEnvelope<AccountCreatedPayloadV1>`，只使用事件 ID、类型、版本和 accountId；
它可以依赖纯 Java 的事件契约，但不能依赖 `ObjectMapper`、`JsonNode`、AMQP Message 或 Decoder。
本轮不要求应用层永远不知道事件元数据，也不为这种理论隔离再创建输入类。

### 3.3 扫描与引用联动

- auth 的 `@MapperScan` 改为本服务 `infrastructure.persistence` 和 `infrastructure.outbox`；以 `annotationClass = Mapper.class` 过滤。
- user 的 `@MapperScan` 改为本服务 `infrastructure.persistence`，同样限定 `@Mapper`。
- 当前五个 Mapper 均已有 `@Mapper`；不得通过扫描整个 `com.calles.platform` 解决漏扫，也不得跨服务扫描。
- 检索全仓旧包名，处理源码、测试、配置中的真实引用；仅陈述历史的 ADR 不做包名批量替换。
- 不修改方法名、Bean 名称、SQL、表注解、路由或调度开关；不得留下新旧组件并存造成重复注册。

## 4. 类型化消费的明确实现边界

### 4.1 类型与绑定

保留 `common-core` 中现有 `EventEnvelope<T>`，不增加 Jackson 注解或业务字段。
两端本地 `AccountCreatedPayloadV1` 均声明 `accountId`、`accountType`、`createdAt` 三个字符串字段。
user 的载荷是纯 Java Record；Decoder 使用明确的 `EventEnvelope<AccountCreatedPayloadV1>` 泛型类型创建 Reader，
不得使用裸 `EventEnvelope.class` 导致 payload 变成 Map。

Decoder 在构造时复制 Spring 提供的 ObjectMapper，只配置这一份副本并创建固定 ObjectReader；
不注册为全局 Mapper Bean，不逐条消息复制，也不修改 HTTP JSON 行为。
采用两个局部 mixin（信封、载荷）以及局部字段适配，保持公共信封和应用载荷无 Jackson 依赖。

### 4.2 兼容处理表

| 输入 | 必须保持的结果 | 局部做法 |
| --- | --- | --- |
| 必需字段 eventId/eventType/aggregateId/accountId/accountType | 只接受非空白 JSON 字符串 | 副本关闭文本字段的数字/布尔隐式转换，不启用单元素数组展开；绑定后检查 null/blank |
| version 为数字 | 沿用旧节点转 int 后判断是否为 1 | 字段适配按实际数值类型做 `Number.intValue()`，不得直接用会执行溢出校验的 `getIntValue()` 替代 |
| version 为字符串 | 保留 `"1"`、`"1.9"` 等旧输入 | 字段适配复用当前 Jackson 的 `NumberInput.parseAsInt(text, -1)`；禁止改成 `Integer.parseInt` |
| version 为布尔值 | true 接收为 1，false 拒收 | 局部适配为 1/0，之后统一只接受 1 |
| version 缺失/null/对象/数组 | 拒收 | 归为无效值，容器值跳过子节点；不使用默认版本 1 |
| producer、occurredAt、payload.createdAt | 缺失或任何 JSON 类型都不新增拒收 | 通过局部 mixin 显式忽略，不绑定 String 后再忽略异常；user 中对应值可为空 |
| traceId | 非文本、空白、不安全均不影响业务接收 | 字段适配只保留文本，其他值跳过并转 null；绑定后按已有正则检查 |
| 根及载荷未知字段 | 忽略 | 在局部 Reader 上允许未知字段，嵌套未知值也跳过 |
| eventId、主体、类型 | 保留既有 UUID、32位账号 ID、user 类型、aggregateId 一致性校验 | 继续使用原校验规则，不顺手收紧 UUID 格式或更改账号语义 |

**生产 Decoder 不再读 JsonNode、Map 或逐字段 `path/get`；也不为整个事件编写手工流式解析器。**
流式适配只处理 version 和 traceId 的旧类型行为，其余结构由对象绑定表达。
局部辅助单元预计为两份 mixin、版本适配和 traceId 适配；它们虽可就近嵌套，仍要注释、测试并计入阅读成本。
若实际需要更多一层层适配或无法满足差分门槛，停止扩大实现，提交具体不兼容样本回到方案审查。

本地已静态核对缓存 Jackson 2.17.1 的相关 API/字节码；**这不是新 Decoder 的可行性测试结果**。
执行前以依赖树确认实际版本，不升级依赖解决问题。数值溢出、重复 JSON 字段、空体、尾随内容等容易造成
树解析与对象绑定差异，必须补新旧差分样本；不得宣称 `Number.intValue()` 已覆盖所有解析条件。
未约定的边界若出现差异，也不能静默放宽/收紧，应记录样本并由审查决定如何保持兼容。

### 4.3 消费事务和追踪不变

Consumer 获取类型化信封，使用其安全 traceId 或既有 messageId/UUID 兜底，然后直接调用 Processor。
Processor 必须继续是独立 Spring Bean，其 `@Transactional` 由监听器外部调用生效。
成功指标仅在 Processor 提交成功返回后记录；协议异常不重新入队，临时异常交给已有有限重试。
成功、协议失败、业务失败路径均恢复消费前 MDC。不得将 Processor 合并到 Consumer 导致事务自调用失效。

## 5. 实施步骤与每步退出条件

以下步骤只有在独立方案审查通过、用户授权实施后执行。

| 步骤 | 动作 | 退出条件 |
| --- | --- | --- |
| 0 冻结基线 | 记录 status、受影响源码/测试/文档完整清单及内容快照，包含未跟踪文件；核对 JDK/依赖；运行现有测试 | 能区分用户原始修改与本次修改；失败已分类，不用 HEAD 冒充基线 |
| 1 只迁目录 | 按第3节移动类及测试，更新导入/MapperScan/注释；不改消息解析和业务方法体 | 编译、原测试及最小组件上下文检查通过，无漏扫/重复 Bean/旧包残留 |
| 2 类型化 | 增加载荷、Decoder 局部 Reader；更新 Consumer/Processor 签名；删除两个旧包装 | 兼容差分、两端契约样本及消费者/处理器测试通过，生产链无树解析或中间投影 |
| 3 集成回归 | 在已确认隔离的 MySQL/Redis/RabbitMQ 及本地服务执行第6节矩阵 | 事务、扫描、重试和资料行为有实际证据；未执行项明确阻塞最终验收 |
| 4 文档与交接 | 同步第7节列出的受影响说明，整理本次差异、证据和回退 | 无“目标已实现”误标，交给未参与实施者独立验收 |

不得在步骤1中顺手改变补齐 Job、Outbox 启停方式、日志字段、权限或账号校验。
现有缺陷可记录，但不以修复它为理由扩大本轮。

## 6. 可复述的验证方法

### 6.1 JDK17 与无冒号验证副本

当前终端默认 Java 为21，工程目标为17；本机存在 `/usr/lib/jvm/java-17-openjdk-amd64`。
下面是**实施时**运行命令，本轮编写方案没有执行构建。基线和修改后分别创建副本，不覆盖基线副本。

```bash
set -euo pipefail
ROOT='/home/fanc/documents/package/codes/java/steps/media-platform:rebuild'
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version
WORK="$(mktemp -d /tmp/media-platform-auth-user-check.XXXXXX)"
# 验证副本不是完整回退备份；不复制凭据、Git 元数据或旧构建结果。
rsync -a --exclude='.git/' --exclude='target/' --exclude='.env' --exclude='.env.*' \
  --exclude='*.pem' --exclude='*.key' --exclude='*.p12' --exclude='*.jks' \
  --exclude='*.log' --exclude='.idea/' "$ROOT/" "$WORK/"
cd "$WORK"
# 不使用 -DskipTests；同时测试/编译所需公共模块，避免复用旧 common 制品。
./mvnw -pl service/auth-service,service/user-service -am clean verify
```

若自定义本地配置包含凭据，复制前按文件清单补排除；不读取或输出其值。不自动删除副本中的证据。
在成功 reactor 验证后使用 `dependency:tree` 检查 user 及所需公共模块：
`./mvnw -pl service/user-service -am dependency:tree -Dincludes=com.fasterxml.jackson.core`。
保留命令、JDK、依赖树、各模块测试数量和失败摘要；不能把“打包成功/测试编译成功”写成“测试通过”。

### 6.2 自动化测试增补（不新增测试框架）

- 原16个测试按新包迁移，不能靠减少测试或放宽断言获得通过。
- `AccountCreatedEventFactoryTest`：v1 信封/载荷、稳定身份、UTC 文本、敏感字段不进入消息。
- Decoder 测试：表4.2逐项覆盖全部必需字段；正常/错误标量、未知嵌套字段、非法主体和版本。
- 将旧 Decoder 作为**测试范围参考实现**做差分：结果比较接受/拒绝、业务字段、安全 traceId，
  不要求内部异常栈相同；覆盖大整数、长整型窄化、负数、小数、科学计数、空白文本、重复字段等。
  完成差分后保留冻结样本断言，不让旧生产解析器作为运行时兼容分支长期存在。
- 两端使用内容相同、无敏感信息的静态 v1 样本：auth 验证输出结构、user 验证输入结果。
  字段顺序不参与比较；不新增共享测试模块，也不让 user 测试依赖 auth 服务制品。
- Consumer：拒收/重试分类、三条退出路径的 MDC 恢复、提交后指标；Processor：五类处理结果及重复登记跳过。
- 用已有 Spring 测试能力补局部扫描探针：在受控配置中加载真实扫描配置与 Mapper，断言五个 Mapper 分属
  各自服务、无重复 Bean、Processor 事务代理存在。仅 mock Mapper 的单测不能替代扫描探针或真实事务验证。
- Decoder 专用 Mapper 的策略不影响 Spring HTTP Mapper；补已有 HTTP DTO 的合法绑定回归，不新增全局配置。

### 6.3 隔离集成与启动验收

先确认 Docker 守护进程可用及目标资源归属，再准备测试专用数据库、Redis namespace、RabbitMQ vhost 和账号。
所有服务绑定本机，阻断生产/共享配置覆盖；Nacos 远程导入在测试配置中明确禁用或指向专用测试环境。
配置只在受控临时环境提供，不能连接未知环境，也不把凭据写入报告。不要直接启用仓库 Compose 来猜环境安全。

| 场景 | 必须观察到的证据 |
| --- | --- |
| 启动与扫描 | auth/user 启动成功，Mapper 实际 SQL 可执行，定时器/监听器注册符合现有开关 |
| 注册正常/回滚 | 正常注册产生账号和 Outbox；故障注入时同成同败，没有只保存账号的半成品 |
| 发布确认/故障恢复 | ack且未退回才标记已发布；不可路由、超时、Broker暂不可用保留可重试状态 |
| 租约竞争 | 并发领取不重复占有有效租约；过期可恢复，旧 claim token 不能覆盖新领取结果 |
| 消费事务/重复 | 同 eventId 重投不重复改变资料；资料写入故障时消费登记一起回滚，重试后可完成 |
| 资料保护 | 已存在、停用、逻辑删除资料不被事件覆盖/复活；事件与 PATCH 竞争保持原规则 |
| 非法消息/死信 | 错误版本/主体拒收；临时故障有限重试，耗尽进入专用测试死信队列 |
| HTTP 回归 | 通过隔离网关验证注册、登录、刷新、注销、verify/me及现有资料读写；状态码/字段/权限保持 |
| 补齐回归 | 原有默认关闭/dry-run无额外写入；只有隔离测试配置明确启用时才验证进度与Outbox同事务 |

HTTP 测试使用既有契约，不凭空编造路由；受控直连仅用于内部启动/扫描诊断，不代替客户端网关验收。
测试故障仅注入本轮专用资源；不得重放、清空、重置生产消息或资料。资源不能确认隔离则停止这一层，
结论写“单元/静态验证完成，集成验收阻塞”，不能宣布最终验收通过。

## 7. 文档同步与影响白名单

- 本方案是唯一详细实施说明；`docs/README.md` 仅提供待审查入口，不新建工作记录目录。
- 获批实施时在 `docs/adr/` 以当时可用编号新增短 ADR：沿用通用信封/本地载荷，替代 ADR0003 中
  “树解析后投影 Input”的实现决策；不改写 ADR0002/0003 历史正文，不撤销既有可靠性决定。
- `docs/architecture.md`：更新实际包职责和类型链，不将领域标记为已迁移。
- `docs/guides/coding-standards.md`：补充本次实际采用的目录规则，不宣称其他服务已经迁好。
- `docs/guides/testing.md`：同步迁移后的测试定位、新增兼容矩阵与真实执行证据。
- `docs/contracts/events/auth.account.created.v1.md`：仅在需要时补实现/验证引用，不改变接收语义和字段。
- `docs/reference/authentication.md`：仅修改涉及本轮代码位置与链路的说明；没有相关引用则不改。
- `.env.example`、Compose、YAML、POM、SQL、HTTP/JWT 契约、网关源码均不在行为变更范围；
  如执行中发现必须改动这些文件的运行语义，先停止扩展并审查。治理文件 AGENTS/CLAUDE 本轮不改。

## 8. 回退与最终门槛

### 工作区回退

实施前受影响文件包含大量未跟踪代码，`git diff` 或 `git restore` 不能完整代表用户基线。
必须先保存受影响文件内容、是否存在及原路径清单，备份放工作区外受控位置；源码验证副本不替代该备份。
回退只撤销本次迁移/类型化差异：核对文件没有后续用户修改后恢复原路径，删除本次新增文件。
遇到重叠修改先人工合并，不使用 `git reset --hard`、`git clean` 或整目录覆盖。

### 制品回退

由于消息体和表结构不变，新旧 auth/user 制品应能互相收发同版事件；发布前用双向样本验证，不仅凭设计判断。
隔离验证中回退到基线制品并复测积压继续消费；真实部署/暂停消费者须另行授权。
不删除任何表、不修改Outbox投递状态、不清空队列/死信、不回滚用户资料或补齐进度。

### 待独立审查的检查表

- [ ] 第3节映射覆盖当前代码且不过度迁移；没有新增模块、表或通用框架。
- [ ] 认可以纯 Java 信封/本地载荷直接作为 Processor 参数，移除两个中间包装。
- [ ] 局部类型化对现有 v1 输入结果保持兼容；不把“将来应严格”混进当前版本。
- [ ] 事务、Mapper扫描、MDC、确认/重试/幂等/资料保护有对应验证和失败出口。
- [ ] 基线与回退包含未跟踪文件；环境不满足时不会操作未知数据库或冒充验收。

审查者给出“通过 / 修改后复审 / 不通过”及阻断项；原方案不预填通过。2026-09-06 的实施是
基于用户明确授权，不等同于独立方案审查通过；最终仍须由未参与实施者按本节清单验收。

本方案形成时只证明方案已经整理和静态核对。后续实施的模块 `verify` 结果以测试指南和交付说明为准，
不证明启动、真实消息链路或隔离集成验收已经通过。
