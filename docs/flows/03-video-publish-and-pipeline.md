# 服务调用主线 03：视频创作、提审探活、异步机审与分级门禁流水线

本文档是媒体平台的**核心业务支柱与技术皇冠**。深入梳理创作者视频创建、Feign 同步文件探活、RabbitMQ 异步流水线任务分解（内容安全机审、多规格切片转码、多模态语义向量）、工业级**分级就绪门禁（`PublishGatekeeper`）**裁决、级联熔断以及超时自愈补偿的端到端服务调用全流程。

---

## 1. 跨服务协同架构全景

视频发布并非简单的单体保存，而是由**内容服务中心协调、跨微服务分工并行、门禁最终裁决**的分布式流水线：

```mermaid
flowchart TB
    Creator(["创作者"])
    GW["API Gateway<br/>gateway-service"]
    CS["内容服务 (content-service)<br/>聚合根与短码 vid 发号<br/>门禁决策器 PublishGatekeeper"]
    FS["文件服务<br/>file-service"]
    MQ[["RabbitMQ 领域事件总线"]]
    Audit["审核服务 (audit-service)<br/>DFA 文本敏感词过滤<br/>综合仲裁引擎"]
    Transcode["转码服务 (transcode-service)<br/>FFmpeg 硬件限流压制<br/>720P/1080P/4K 切片"]
    VectorWorker["AI 计算集群<br/>多模态向量特征提取"]

    %% 提审与探活
    Creator -->|1. 提交视频提审 POST submit| GW
    GW --> CS
    CS ==>|2. OpenFeign 同步探活视频与封面状态| FS
    
    %% 本地事务与事件派发
    CS -->|3. 事务初始化 5 大子任务并写 Outbox| CS
    CS -.->|4. 广播提审事件 content.video.submitted| MQ
    
    %% 多路并发消费
    MQ -.->|消费| Audit
    MQ -.->|消费| Transcode
    MQ -.->|消费| VectorWorker
    
    %% 审核拉流与机审
    Audit ==>|Feign 换取临时拉流直链| FS
    Audit ==>|5. OpenFeign 内部机审专用回调| CS
    
    %% 转码切片与产物托管
    Transcode ==>|Feign 上传切片文件| FS
    Transcode ==>|6. OpenFeign 内部转码切片专用回调| CS
    
    %% 向量汇报
    VectorWorker -->|7. HTTP 内部任务状态汇报| CS
    
    %% 门禁闭环
    CS -->|8. 门禁达成自动上线 PUBLISHED| MQ
```

---

## 2. 端到端执行时序图 (End-to-End Sequence)

```mermaid
sequenceDiagram
    autonumber
    participant Creator as 创作者
    participant GW as gateway-service
    participant CS as content-service
    participant FS as file-service
    participant MQ as RabbitMQ
    participant Audit as audit-service
    participant TS as transcode-service

    %% 第一步：创建草稿与提审探活
    rect rgb(240, 248, 255)
    Note over Creator,TS: 步骤一 创作者提审与文件资产前置强探活
    Creator->>GW: POST /api/content/videos/{id}/submit
    GW->>CS: 转发提审请求 (透传 X-User-Id)
    CS->>CS: 校验作者所有权与状态 (必须为 DRAFT 或 REJECTED)
    CS->>FS: OpenFeign: GET /api/files/{videoFileId}/metadata
    FS-->>CS: 检查通过 (status=ACTIVE 且 uploadStatus=COMPLETED)
    CS->>FS: OpenFeign: GET /api/files/{coverFileId}/metadata
    FS-->>CS: 检查通过 (封面资产有效且就绪)
    CS->>CS: 本地数据库事务：<br/>1. 视频状态更新为 AUDITING<br/>2. 批量初始化 5 大流水线子任务 (AUDIT, 720P, 1080P, 4K, VECTOR)<br/>3. 写入 content_outbox 发件箱表
    CS-->>GW: 返回 200 OK (受理成功，状态转入 AUDITING)
    GW-->>Creator: 响应提审受理成功，前端展示流水线进度条
    end

    %% 第二步：异步事件分发
    rect rgb(255, 250, 240)
    Note over Creator,TS: 步骤二 事务发件箱 Transactional Outbox 异步广播
    CS->>MQ: 投递提审事件 content.video.submitted (包含 videoId, vid, authorId, 资产ID)
    MQ->>Audit: 路由至 audit-service 专属队列消费
    MQ->>TS: 路由至 transcode-service 专属队列消费
    end

    %% 第三步：机审流水线与专有回调
    rect rgb(240, 255, 240)
    Note over Creator,TS: 步骤三 合规机审多维判定与门禁结果对齐
    Audit->>Audit: DFA 算法毫秒级扫描标题与简介文本敏感词
    Audit->>FS: Feign: 获取封面与视频临时预签名拉流直链
    Audit->>Audit: 驱动阿里云内容安全或本地规则桩执行多媒体机审
    Audit->>Audit: 综合仲裁引擎执行最高风险优先判定 (NORMAL 合规)
    Audit->>CS: OpenFeign: POST /api/content/videos/internal/audit-callback (PASS)
    Note over CS: 更新 AUDIT 任务为 SUCCESS<br/>触发 PublishGatekeeper 门禁重新评估
    CS-->>Audit: 200 OK 确认回调
    end

    %% 第四步：流媒体转码与切片注册
    rect rgb(255, 245, 245)
    Note over Creator,TS: 步骤四 音视频压制与流切片资产登记
    TS->>TS: RateLimiter 信号量获取本地硬件并发许可 (保护服务器 CPU)
    TS->>FS: Feign: 获取原片预签名下载直链，流式拉入独立沙箱
    TS->>TS: FFmpeg 等比缩放、黑边填充、FastStart 压制 720P 与 1080P
    TS->>FS: Feign: POST /api/files/internal/upload (免密托管上传切片)
    FS-->>TS: 签发新切片 outputFileId
    TS->>CS: OpenFeign: POST /api/content/videos/internal/transcode-callback (登记 720P/1080P 规格与时长)
    Note over CS: 持久化 video_stream 记录<br/>更新对应转码任务为 SUCCESS<br/>触发 PublishGatekeeper 门禁重新评估
    CS-->>TS: 200 OK 确认回调
    end

    %% 第五步：分级门禁达成与正式上线
    rect rgb(245, 240, 255)
    Note over Creator,TS: 步骤五 分级就绪门禁裁决与全站广播
    Note over CS: PublishGatekeeper 判定公式达成：<br/>(AUDIT=SUCCESS) 且 (720P或1080P=SUCCESS) 且 (VECTOR=SUCCESS)
    CS->>CS: 本地数据库事务：<br/>1. 视频状态跃迁为 PUBLISHED<br/>2. 记录首次公开发布时间 published_at<br/>3. 写入 Outbox 事件 content.video.published
    CS->>MQ: 广播 content.video.published 领域事件
    Note over MQ: 搜索引擎构建索引、推荐流计算候选特征、站内信通知作者
    end
```

---

## 3. 核心技术机制深度剖析

### 3.1 提审前 OpenFeign 强探活机制
在分布式音视频架构中，最忌讳“脏引用与空壳提审”——即用户传入了未经确认的 `PENDING` 文件 ID 或伪造的 ID，导致系统调动大量 GPU/CPU 算力去转码一个根本不存在的文件。

- **探活客户端**：[`FileServiceClient.getFileMetadata(fileId)`](../../service/content-service/src/main/java/com/calles/platform/content/application/client/FileServiceClient.java)
- **校验硬指标**：
  1. 主视频文件与封面图必须在 `file_asset` 表中存在；
  2. 文件的逻辑可用状态必须为 `status = 'ACTIVE'`；
  3. 文件的上传确认状态必须为 `upload_status = 'COMPLETED'`；
  4. 若任一检查不满足，`content-service` 在提审阶段立即抛出 `400 BAD_REQUEST` 并附带明确中文提示，彻底杜绝下游空转。

---

### 3.2 分级就绪门禁决策器 (`PublishGatekeeper`)
传统音视频网站在“全部分辨率（包含 4K、AV1 等重度规格）压制完成前”禁止上线，导致创作者发布延迟极大。`content-service` 引入工业级**分级就绪门禁**：

```mermaid
flowchart TD
    subgraph Tasks [5 类异步子任务状态]
        T1["AUDIT (内容安全机审)"]
        T2["TRANSCODE_720P (基准画质)"]
        T3["TRANSCODE_1080P (基准画质)"]
        T4["TRANSCODE_4K (长耗时超清)"]
        T5["VECTOR_EMBEDDING (语义特征)"]
    end

    subgraph Gatekeeper [PublishGatekeeper 决策核心]
        AuditDecision{"机审是否通过?"}
        BaseStreamDecision{"720P 或 1080P<br/>至少一路就绪?"}
        VectorDecision{"向量特征提取成功?"}
        
        FinalCheck{"门禁公式达成?"}
    end

    T1 --> AuditDecision
    AuditDecision -- 驳回 REJECTED --> CascadeCancel["级联熔断<br/>视频流转 REJECTED<br/>自动取消所有在途转码与计算"]
    AuditDecision -- 成功 SUCCESS --> FinalCheck

    T2 --> BaseStreamDecision
    T3 --> BaseStreamDecision
    BaseStreamDecision -- 是 --> FinalCheck

    T5 --> VectorDecision
    VectorDecision -- 成功 SUCCESS --> FinalCheck

    FinalCheck -- 满足条件 --> AutoPublish["自动上线<br/>视频跃迁为 PUBLISHED<br/>广播 content.video.published"]

    T4 -.->|长耗时异步完成| SilentAdd["静默追加<br/>写入 video_stream<br/>前台观众无感新增 4K 选项"]
```

#### 1. 门禁准入公式 (数学逻辑)
$$\text{GateReady} = (\text{Task}_{\text{AUDIT}} = \text{SUCCESS}) \land (\text{Task}_{\text{720P}} = \text{SUCCESS} \lor \text{Task}_{\text{1080P}} = \text{SUCCESS}) \land (\text{Task}_{\text{VECTOR}} = \text{SUCCESS})$$

- **快速发布**：只要审核通过、至少一条基准清晰度流（720P 或 1080P）就绪且向量计算完毕，视频**立刻自动流转为 `PUBLISHED`**；
- **4K 异步追加**：4K 转码耗时长达数十分钟，其任务完成时仅静默插入一条 `video_stream` 切片，前台画质切换菜单无缝增加 4K 选项，完全不阻塞视频的提前上线；
- **违规即时熔断**：一旦 `audit-service` 判定违规（驳回），门禁立即将视频标为 `REJECTED`，并调用原子 SQL 将处于 `PENDING` 或 `RUNNING` 的其余转码任务统一置为 `CANCELED`，防止空耗转码算力与磁盘空间。

---

### 3.3 重新提审与流水线复苏机制 (`Pipeline Resuscitation`)

当视频被审核驳回或转码异常后，创作者修改标题、简介或更换文件可再次提审。系统具备智能的**流水线复苏能力**：

```mermaid
stateDiagram-v2
    [*] --> PENDING : 提审初始化
    PENDING --> RUNNING : Worker 开始执行或收到首个进度包
    RUNNING --> SUCCESS : 任务执行完成且结果登记
    RUNNING --> FAILED : 执行报错或超时耗尽重试
    RUNNING --> CANCELED : 机审违规熔断或人工终止
    
    FAILED --> PENDING : 重新提审复苏流水线
    CANCELED --> PENDING : 重新提审复苏被取消的任务
    SUCCESS --> SUCCESS : 已成功的切片流保持有效复用
```

- 在 [`VideoTaskCoordinator.resetPipelineTasksForResubmit(...)`](../../service/content-service/src/main/java/com/calles/platform/content/application/task/VideoTaskCoordinator.java) 中：
  - 已完成的 `SUCCESS` 切片保留，不重复转码；
  - 处于 `FAILED` 或 `CANCELED` 的子任务被原子重置为 `PENDING`，重试计数清零，错误信息重置；
  - 视频状态由 `REJECTED` 重新跃迁为 `AUDITING`，发件箱重新向 RabbitMQ 广播提审事件。

---

### 3.4 僵死任务巡检与超时自愈调度器 (`VideoTaskTimeoutScheduler`)

在分布式 Worker 集群中，进程崩溃、网络断开或宿主机 OOM 可能导致子任务永久停留在 `RUNNING` 状态：
- **定时调度**：后台调度器每分钟扫描处于 `RUNNING` 状态且持续时长超过阈值（默认 15 分钟，通过 `content.task.timeout-minutes` 配置）的僵死任务；
- **自愈补偿策略**：
  - 若 `retryCount < maxRetries`（默认 3 次）：将任务重置为 `PENDING`，自增 `retryCount` 重新进入派发排队；
  - 若已达最大重试上限：标记任务为 `FAILED`；若失败的是阻断性 `AUDIT` 任务，联动门禁驳回视频并终止流水线，防止作品处于永久挂死状态。

> 💡 **模块细查**：
> - 完整门禁决策与用例代码详见 [内容模块 · content-service](../modules/content.md)。
> - 审核多维规则与仲裁引擎详见 [审核模块 · audit-service](../modules/audit.md)。
> - 物理转码与硬件限流机制详见 [转码模块 · transcode-service](../modules/transcode.md)。
