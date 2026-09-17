# 审核模块 · audit-service 架构设计与实现文档

审核模块（`audit-service`）是平台内容安全、合规风控与法律防线业务支柱。它负责接收来自内容服务（`content-service`）的提审事件，对视频图文元数据（标题、简介）、多媒体封面图片以及音视频资产执行分级安全合规机审，生成完备的多维度审查证据与日志，并通过专用内部微服务回调机制驱动内容服务的分级门禁流转。

---

## 1. 模块定位与架构边界

### 1.1 业务职责边界
- **提审事件异步受理**：通过 RabbitMQ 消费来自 `content-service` 的 `content.video.submitted` 提审事件，解析视频主键、公开编码、作者信息及关联文件资产凭证；
- **分级自动化机审流水线**：
  - **文本元数据审查**：基于确定有限状态自动机（DFA）前缀树算法，对标题、简介等文本进行毫秒级敏感词过滤与分级研判；
  - **多媒体封面审查**：基于规则校验与可插拔适配器（支持本地规则桩演练与**阿里云内容安全 2.0 增强版** `imageModeration` 适配层）校验封面图合规性，通过 `file-service` 换取 MinIO 短期预签名 URL 拉流；
  - **视频流资产合规性**：支持本地规则桩与**阿里云内容安全 2.0 增强版** `videoModeration` 双轨结果接收（开发环境本地主动轮询降级 + 生产环境异步 Webhook 回调带 SHA-256 防篡改验签）；
- **聚合仲裁决策**：采用最高风险等级优先策略（`ILLEGAL` > `SUSPICIOUS` > `NORMAL`）聚合各维度明细，判定是直接通过、违规驳回还是转入人工复审；
- **高韧性下游回调与状态对齐**：通过 OpenFeign 调用内容服务专属内部回调端点 `POST /api/content/videos/internal/audit-callback`，驱动内容状态流转；内置指数退避定时重试调度器（`AuditCallbackRetryScheduler`），避免瞬时网络抖动导致任务卡死。

### 1.2 防腐与不应承担的工作
- **不直接修改视频业务表**：视频的状态维护由 `content-service` 完全拥有，本服务仅提供判定结论与驳回原因；
- **不直接托管二进制文件**：音视频与图片物理存储由 `file-service` 负责，本服务仅依赖文件资产 ID 进行元数据判定或受控临时拉流；
- **不直接管理账号身份**：依赖网关校验 JWT 并透传 `X-User-Id` 与 `X-User-Role`。

---

## 2. 领域模型与核心状态机

### 2.1 聚合根与实体拓扑

```mermaid
classDiagram
    class AuditTask {
        +String id (PK, UUID)
        +String taskNo (业务编号 aud_xxx)
        +String bizType (业务类型: VIDEO)
        +String bizId (业务ID, videoId)
        +String bizVid (业务对外编码, cv...)
        +String authorId
        +String titleSnapshot
        +String descriptionSnapshot
        +String coverFileId
        +String videoFileId
        +AuditStage stage
        +AuditResult result
        +String rejectReason
        +ReviewLevel reviewLevel
        +String operatorId
        +CallbackStatus callbackStatus
        +int callbackRetries
        +startMachineAudit()
        +completeMachineAudit()
        +approveByManual()
        +rejectByManual()
        +markCallbackSuccess()
        +markCallbackFailed()
    }

    class AuditDetail {
        +String id (PK, UUID)
        +String taskId (FK)
        +AuditDimension dimension
        +String engineType
        +ReviewLevel level
        +BigDecimal confidence
        +String hitWords
        +String detailLog
    }

    class AuditSensitiveWord {
        +String id (PK, UUID)
        +String word
        +WordCategory category
        +WordLevel level
        +CommonStatus status
    }

    AuditTask "1" *-- "1..*" AuditDetail : 包含多维度判定证据
```

### 2.2 状态流转状态机

```mermaid
stateDiagram-v2
    [*] --> RECEIVED : 接收 content.video.submitted
    RECEIVED --> MACHINE_AUDITING : 启动机审 (startMachineAudit)
    
    MACHINE_AUDITING --> FINISHED_PASSED : 全维度 NORMAL 放行 (result=PASSED)
    MACHINE_AUDITING --> FINISHED_REJECTED : 任一维度 ILLEGAL 阻断 (result=REJECTED)
    MACHINE_AUDITING --> MANUAL_PENDING : 命中 SUSPICIOUS (转人审工单)
    
    MANUAL_PENDING --> FINISHED_PASSED : 管理员人工通过 (approveByManual)
    MANUAL_PENDING --> FINISHED_REJECTED : 管理员人工驳回 (rejectByManual)
    
    FINISHED_PASSED --> CALLBACKING : 触发内容服务回调 (passed=true)
    FINISHED_REJECTED --> CALLBACKING : 触发内容服务回调 (passed=false, reason)
    
    CALLBACKING --> [*] : 回调成功 (callbackStatus=SUCCESS)
    CALLBACKING --> CALLBACK_RETRY : 网络失败 (callbackStatus=FAILED)
    CALLBACK_RETRY --> [*] : 补偿调度重试成功
```

---

## 3. 数据库表结构

表统一存放在 MySQL 实例中，使用前缀 `audit_` 隔离表所有权，主键为 32 位 UUID，时间保留毫秒精度 `DATETIME(3)`。

- **审核主任务表 (`audit_task`)**：独立 DDL 脚本位于 [`service/audit-service/db/schema/audit-task.sql`](../../service/audit-service/db/schema/audit-task.sql)；
- **审核判定明细表 (`audit_detail`)**：独立 DDL 脚本位于 [`service/audit-service/db/schema/audit-detail.sql`](../../service/audit-service/db/schema/audit-detail.sql)；
- **敏感词库字典表 (`audit_sensitive_word`)**：独立 DDL 脚本位于 [`service/audit-service/db/schema/audit-sensitive-word.sql`](../../service/audit-service/db/schema/audit-sensitive-word.sql)；
- **全局建表汇总**：已合并至根目录 [`db/init/schema.sql`](../../db/init/schema.sql)。

---

## 4. 可插拔审核引擎与通信契约

### 4.1 审核引擎群与策略编排
- **引擎抽象契约与值对象**（`domain/engine/`）：
  - `TextAuditEngine`、`ImageAuditEngine`、`VideoAuditEngine`、`EngineAuditResult`
- **引擎基础设施具体实现**（`infrastructure/engine/`）：
  - **`DfaTextAuditEngine`**：基于确定有限状态机（DFA）敏感词前缀树扫描，时间复杂度为 $O(N)$；支持大小写与噪声干扰字符过滤；支持数据库热重载与本地内置默认兜底词库；
  - **`DefaultRuleImageAuditEngine`**：封面多媒体规则审查桩（`audit.aliyun.enabled: false` 时生效），用于离线规则演练与无外部网络测试；
  - **`AliyunGreenImageAuditEngine`**：阿里云内容安全 2.0 增强版图片审核引擎（`audit.aliyun.enabled: true` 时生效）；通过 OpenFeign 调取 `file-service` 获取 MinIO 预签名拉流 URL，向阿里云提交 `imageModeration`（服务编码 `baselineCheck`），解析多标签违规项并聚合全局风险级别；
  - **`DefaultVideoAuditEngine`**：视频规格与合规性审查桩（`audit.aliyun.enabled: false` 时生效）；
  - **`AliyunGreenVideoAuditEngine`**：阿里云内容安全 2.0 增强版视频机审引擎（`audit.aliyun.enabled: true` 时生效）；通过 `file-service` 获取视频拉流直链并提交 `videoModeration` 任务，支持双轨结果接收；
- **领域决策仲裁服务**（`domain/service/`）：
  - **`AuditDecisionAggregator`**：综合仲裁器，基于安全最高优先级汇总判定与驳回理由；
- **业务专属执行器路由分派体系**（`application/executor/`）：
  - 基于策略模式与路由组件（`AuditExecutorRouter`）解耦通用流程与业务类型特化逻辑，包含 `AuditExecutor`、`VideoAuditExecutor`、`AuditContext` 与 `AuditExecutionResult`；
  - **三阶段异步并发与容灾降级**：`VideoAuditExecutor` 基于 `auditEngineExecutor` 专有线程池将文本（标题/简介）、封面图片与主视频资产审查异步并发调度，统一 `CompletableFuture.allOf` 等待，并将单项异常降级为 `SUSPICIOUS`（人工复审）；
  - **重提增量免审复用机制**：`AuditTaskCoordinator` 在接收到重新提审事件时，自动比对前序终局驳回记录的资产指纹，若封面图或视频文件未发生变动且历史判定已为 `NORMAL`，则直接继承历史合规结论，跳过相应引擎的重复计算。

### 4.2 阿里云内容安全 2.0 配置与双轨接收架构

#### 4.2.1 环境变量与配置项
配置项统一位于 `audit.aliyun.*`，默认处于脱网安全状态（`enabled: false`）：
- `audit.aliyun.enabled`：总开关，默认 `false`（回退为本地规则桩）；
- `audit.aliyun.access-key-id` / `access-key-secret`：阿里云 RAM 凭证（生产通过环境变量 `ALICLOUD_ACCESS_KEY_ID` / `ALICLOUD_ACCESS_KEY_SECRET` 注入）；
- `audit.aliyun.endpoint`：Green API 服务地址，默认 `green-cip.cn-shanghai.aliyuncs.com`；
- `audit.aliyun.uid` / `audit.aliyun.seed`：异步 Webhook 回调签名防篡改校验参数（通过 `ALICLOUD_GREEN_UID` / `ALICLOUD_GREEN_SEED` 注入）；
- `audit.aliyun.callback-url`：云端 Webhook 回调接收地址（如 `https://example.com/api/audit/callback/aliyun/video`）；
- `audit.aliyun.image-service-code` / `video-service-code`：机审服务策略，默认均为 `baselineCheck`；
- `audit.aliyun.polling-timeout-seconds`：本地内网轮询最大等待时间（默认 15 秒）；
- `audit.aliyun.short-probe-timeout-seconds`：云端模式前置短探测等待时间（默认 3 秒）。

#### 4.2.2 双轨结果接收架构
针对开发者本地内网（无公网 IP）与云端线上部署差异，设计“双轨结果接收机制”：
- **轨道 A（本地内网轮询模式，`callback-url` 为空）**：
  引擎提交检测任务后，每隔 1.5 秒主动轮询 `client.videoModerationResult(...)`。若在 15 秒超时内出结果则直接完结；若超时则优雅返回 `SUSPICIOUS`（人工复审保底），不阻断流水线。
- **轨道 B（云端异步 Webhook 模式，`callback-url` 已配置）**：
  引擎先执行 3 秒短探测（覆盖小视频秒级出结果场景）；若未出结果则返回中间挂起态 `SUSPICIOUS`。当阿里云机审完成后，主动回调网关放行的端点 `POST /api/audit/callback/aliyun/video`。应用层通过 `AliyunAuditCallbackApplicationService` 执行 `SHA-256(uid + seed + content)` 签名防篡改校验，持久化视频维度判定证据，并调用 `AuditTask.completeAsyncMachineAudit(...)` 完成终局跃迁并驱动下游门禁回调。

### 4.3 跨服务通信与回调契约
1. **提审事件消费**：
   - 监听 RabbitMQ Exchange `media.platform.events`，Queue `audit-service.content-video-submitted.v1`，Routing Key `content.video.submitted`；
   - 具备死信队列 `audit-service.content-video-submitted.v1.dlq` 保证异常消息可追溯。
2. **Feign 判定回调**：
   - 客户端：[`ContentServiceClient.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/client/ContentServiceClient.java)；
   - 目标端点：`POST /api/content/videos/internal/audit-callback`；
   - 载荷参数：`videoId`、`passed`、`rejectReason`。
3. **文件服务拉流授权调用**：
   - 客户端：[`FileServiceClient.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/client/FileServiceClient.java)；
   - 目标端点：`GET /api/files/{id}/download-url`；
   - 载荷参数：获取 MinIO 具备受控有效期的预签名 GET 直链供阿里云异步拉流检测。

### 4.4 HTTP 接口清单

| 方法 | URI 路径 | 权限控制 | 说明 |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/audit/internal/submit` | 内部网络 / 调试 | 模拟发起视频审核流水线 |
| `GET` | `/api/audit/tasks/{id}` | 内部网络 / 统一查阅 | 查看审核任务状态及多维度证据明细 |
| `GET` | `/api/audit/admin/tasks` | 管理员 / 审核员 | 运营后台复合条件分页检索工单列表 |
| `GET` | `/api/audit/admin/tasks/{id}` | 管理员 / 审核员 | 运营后台查看工单全景信息与多维度证据 |
| `POST` | `/api/audit/admin/tasks/{id}/review` | 管理员 / 审核员 | 人工审核放行/驳回裁决并联动下游内容服务 |
| `POST` | `/api/audit/callback/aliyun/video` | 匿名（SHA-256 签名鉴权） | 阿里云内容安全 2.0 视频机审异步 Webhook 回调通知 |

---

## 5. 源码核心入口索引

- **引导入口**：[`AuditApplication.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/AuditApplication.java)（开启 OpenFeign 与 定时调度 `@EnableScheduling`）；
- **基础设施与线程池配置**：
  - 线程池配置：[`AuditThreadPoolConfiguration.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/config/AuditThreadPoolConfiguration.java)（定义 `auditEngineExecutor` 专有执行器，基于 Java 21 虚拟线程 SimpleAsyncTaskExecutor）；
  - 阿里云 Green 客户端配置：[`AliyunGreenProperties.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/config/aliyun/AliyunGreenProperties.java)、[`AliyunGreenClientConfiguration.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/config/aliyun/AliyunGreenClientConfiguration.java)；
  - 消息队列拓扑：[`AuditMessagingConfiguration.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/config/AuditMessagingConfiguration.java)
- **跨服务通信与上下文**：
  - 文件服务拉流客户端：[`FileServiceClient.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/client/FileServiceClient.java) 与 [`FileDownloadUrlDTO.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/client/dto/FileDownloadUrlDTO.java)
  - 异步机审线程上下文传递：[`AuditContextHolder.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/executor/model/AuditContextHolder.java)
- **DTO 契约传输层**：
  - 入参契约：[`AuditRequests.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/http/dto/AuditRequests.java)
  - 出参契约：[`AuditResponses.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/http/dto/AuditResponses.java)
- **统一异常与处理器**：
  - 业务异常：[`AuditException.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/exception/AuditException.java)
  - 全局异常切面：[`AuditExceptionHandler.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/http/advice/AuditExceptionHandler.java)
- **领域模型与服务**：
  - 任务聚合根：[`AuditTask.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/domain/model/AuditTask.java)（新增 `completeAsyncMachineAudit` 异步机审终局跃迁）
  - 明细实体：[`AuditDetail.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/domain/model/AuditDetail.java)
  - 敏感词实体：[`AuditSensitiveWord.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/domain/model/AuditSensitiveWord.java)
  - 状态与维度枚举：[`domain/model/enums/`](../../service/audit-service/src/main/java/com/calles/platform/audit/domain/model/enums/)（包含 `AuditStage`、`AuditResult`、`ReviewLevel`、`AuditDimension`、`CallbackStatus`、`CommonStatus`、`WordCategory`、`WordLevel`）
  - 仲裁决策领域服务：[`AuditDecisionAggregator.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/domain/service/AuditDecisionAggregator.java)
- **审查引擎契约与实现**：
  - 顶层统一契约：[`AuditEngine.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/domain/engine/AuditEngine.java)
  - 维度契约接口：[`TextAuditEngine.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/domain/engine/TextAuditEngine.java)、[`ImageAuditEngine.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/domain/engine/ImageAuditEngine.java)、[`VideoAuditEngine.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/domain/engine/VideoAuditEngine.java)
  - 判定结果值对象模型：[`EngineAuditResult.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/domain/engine/model/EngineAuditResult.java)
  - 基础设施规则基类模板：[`AbstractRuleAssetAuditEngine.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/infrastructure/engine/rule/AbstractRuleAssetAuditEngine.java)
  - DFA 文本引擎实现：[`DfaTextAuditEngine.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/infrastructure/engine/text/DfaTextAuditEngine.java)
  - 封面规则引擎桩实现：[`DefaultRuleImageAuditEngine.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/infrastructure/engine/rule/DefaultRuleImageAuditEngine.java)
  - 阿里云图片审核引擎：[`AliyunGreenImageAuditEngine.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/infrastructure/engine/aliyun/AliyunGreenImageAuditEngine.java)
  - 视频规则引擎桩实现：[`DefaultVideoAuditEngine.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/infrastructure/engine/rule/DefaultVideoAuditEngine.java)
  - 阿里云视频机审引擎：[`AliyunGreenVideoAuditEngine.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/infrastructure/engine/aliyun/AliyunGreenVideoAuditEngine.java)
- **应用协调、执行与调度**：
  - 通用工作流协调器：[`AuditTaskCoordinator.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/coordinator/AuditTaskCoordinator.java)（内含增量免审指纹比对）
  - 业务执行器体系：
    - 策略契约与路由：[`AuditExecutor.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/executor/AuditExecutor.java)、[`AuditExecutorRouter.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/executor/AuditExecutorRouter.java)
    - 业务类型与模型：[`AuditBizType.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/executor/model/AuditBizType.java)、[`AuditContext.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/executor/model/AuditContext.java)、[`AuditExecutionResult.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/executor/model/AuditExecutionResult.java)
    - 业务执行实现：[`VideoAuditExecutor.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/executor/impl/VideoAuditExecutor.java)（三阶段异步并发调度）
  - 人审应用服务：[`AuditManualReviewApplicationService.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/service/AuditManualReviewApplicationService.java)
  - 阿里云 Webhook 应用服务：[`AliyunAuditCallbackApplicationService.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/service/callback/AliyunAuditCallbackApplicationService.java)
  - 回调服务：[`AuditCallbackService.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/service/AuditCallbackService.java)
  - 容灾补偿定时器：[`AuditCallbackRetryScheduler.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/application/scheduler/AuditCallbackRetryScheduler.java)
- **消息与控制器**：
  - 提审消息强类型模型：[`VideoSubmittedMessage.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/messaging/event/VideoSubmittedMessage.java) 与 [`VideoSubmittedPayload.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/messaging/event/VideoSubmittedPayload.java)
  - 提审消息消费者：[`VideoSubmittedConsumer.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/messaging/consumer/VideoSubmittedConsumer.java)
  - 内部端点控制器：[`InternalAuditController.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/http/controller/internal/InternalAuditController.java)
  - 管理端端点控制器：[`AdminAuditController.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/http/controller/admin/AdminAuditController.java)
  - 阿里云回调控制器：[`AliyunAuditCallbackController.java`](../../service/audit-service/src/main/java/com/calles/platform/audit/interfaces/http/controller/callback/AliyunAuditCallbackController.java)

---

## 6. 验证方式与测试覆盖

模块内置 **70 项单元与集成测试用例**（17 个测试类），测试套件涵盖：
1. **`DfaTextAuditEngineTest`**（6 项）：验证 AuditEngine 顶层契约元数据、正常文本放行、严重违禁词拦截、疑似词识别、干扰符过滤及空串边界；
2. **`AbstractRuleAssetAuditEngineTest`**（4 项）：验证图片与视频规则审查引擎基类的维度支持、空资产拦截、违规特征桩拦截、疑似敏感转人审及合规放行；
3. **`AuditDecisionAggregatorTest`**（3 项）：验证全正常仲裁、包含违规最高优先级判定、疑似转人审仲裁；
4. **`VideoAuditExecutorTest`**（5 项）：验证视频专属执行器业务类型声明、多维度机审编排输出、历史免审跳过与引擎异常自动降级为 SUSPICIOUS；
5. **`AuditExecutorRouterTest`**（4 项）：验证执行器路由按 AuditBizType 枚举 O(1) 派发、字符串兼容路由及非法业务类型拦截防护；
6. **`AuditTaskTest`**（5 项）：验证聚合根全生命周期状态流转、人工审批/驳回跃迁及非法跃迁异常；
7. **`AuditCallbackServiceTest`**（3 项）：验证 Feign 远程回调成功更新状态、网络超时异常标记失败重试及未完结状态拦截；
8. **`AuditTaskCoordinatorTest`**（5 项）：验证全流程协调流水线、合规通过自动回调、违规拦截自动打回、疑似可疑转待人审、提审幂等保护及重新提审增量免审复用；
9. **`AuditCallbackRetrySchedulerTest`**（1 项）：验证定时补偿器扫描并重试失败任务；
10. **`VideoSubmittedConsumerTest`**（4 项）：验证标准信封嵌套结构、扁平直传载荷、未知扩展字段兼容及缺失 ID 守卫校验；
11. **`InternalAuditControllerTest`**（3 项）：验证 MockMvc HTTP 模拟提交与任务明细查询（防腐 DTO 结构）；
12. **`AdminAuditControllerTest`**（5 项）：验证管理端分页检索工单、全景详情、人工通过/驳回、非法参数校验拦截；
13. **`AuditManualReviewApplicationServiceTest`**（7 项）：验证人审应用服务全景组装、分页查询、审批流转、驳回原因校验及状态机保护；
14. **`AliyunGreenImageAuditEngineTest`**（5 项）：验证 Client 未初始化降级、正常合规放行、违规标签拦截、疑似敏感转人审、文件拉流 URL 获取失败降级；
15. **`AliyunGreenVideoAuditEngineTest`**（4 项）：验证云端异步 Webhook 探测挂起、内网主动轮询检测通过、内网轮询超时降级转人工、文件拉流失败降级；
16. **`AliyunAuditCallbackApplicationServiceTest`**（4 项）：验证 SHA-256 签名鉴权拦截、未知任务幂等忽略、视频违规自动驳回并驱动下游门禁、视频合规自动放行；
17. **`AliyunAuditCallbackControllerTest`**（2 项）：验证 HTTP 接口成功接收 Webhook、签名校验未通过返回 401。

**全量回归测试指令**：
- 审核模块测试：`./mvnw -f service/audit-service/pom.xml test`（70 项用例 100% 通过）
- 内容模块协同回归：`./mvnw -f service/content-service/pom.xml test`（112 项用例 100% 通过）
