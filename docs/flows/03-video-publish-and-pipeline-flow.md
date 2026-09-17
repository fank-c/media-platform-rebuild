# 服务调用主线 03：视频创作、提审探活、异步机审与分级门禁流水线

本文档是媒体平台的**核心业务支柱与技术皇冠**。深入梳理创作者视频创建、Feign 同步文件探活、RabbitMQ 异步流水线任务分解（内容安全机审、多规格切片转码、多模态语义向量）、工业级**分级就绪门禁（`PublishGatekeeper`）**裁决、级联熔断以及超时自愈补偿的端到端服务调用全流程。

---

## 1. 跨服务协同架构全景

视频发布并非简单的单体保存，而是由**内容服务中心协调、跨微服务分工并行、门禁最终裁决**的分布式流水线：

```mermaid
graph TD
    subgraph ClientAndGateway ["创作者与接入层"]
        Creator["创作者"]
        GW["API网关 (8000)"]
    end

    subgraph ContentCore ["内容聚合中枢"]
        CS["内容服务 (8030)"]
    end

    subgraph AsyncWorkers ["异步工作流节点"]
        Audit["审核服务 (8050)"]
        Transcode["转码服务 (8800)"]
        VectorWorker["AI向量计算集群"]
    end

    subgraph InfraStorage ["文件存储与消息总线"]
        FS["文件服务 (8040)"]
        MQ[["RabbitMQ 领域总线"]]
    end

    %% 提审与探活
    Creator -->|1. 提审请求| GW
    GW -->|/api/content/submit| CS
    CS -->|2. Feign探活资产| FS

    %% 本地事务与派发
    CS -->|3. 拆分5大子任务| CS
    CS -.->|4. 发布 video.submitted| MQ

    %% 异步消费
    MQ -.->|消费| Audit
    MQ -.->|消费| Transcode
    MQ -.->|消费| VectorWorker

    %% 审核拉流与机审回调
    Audit -->|Feign申请拉流| FS
    Audit -->|5. Feign机审回调| CS

    %% 转码切片与产物托管
    Transcode -->|Feign切片托管| FS
    Transcode -->|6. Feign转码回调| CS

    %% 向量计算上报与上线
    VectorWorker -->|7. 向量结果上报| CS
    CS -.->|8. 门禁达成发布上线| MQ
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
graph TD
    subgraph TaskInputs ["五类子任务并发结果输入"]
        T1["AUDIT (机审任务)"]
        T2["TRANSCODE_720P (基准流)"]
        T3["TRANSCODE_1080P (基准流)"]
        T4["TRANSCODE_4K (长耗时超清)"]
        T5["VECTOR_EMBEDDING (语义特征)"]
    end

    subgraph GatekeeperCore ["门禁准入核心 PublishGatekeeper"]
        AuditCheck{"机审是否通过?"}
        BaseStreamCheck{"720P/1080P 至少一路就绪?"}
        VectorCheck{"向量特征是否就绪?"}
        FinalDecision{"准入公式是否达成?"}
    end

    subgraph GateOutputs ["门禁决策动作"]
        CascadeCancel["级联熔断: 标记 REJECTED<br/>取消所有在途子任务"]
        AutoPublish["自动上线: 跃迁 PUBLISHED<br/>广播 video.published"]
        SilentAppend["静默追加: 登记 video_stream<br/>播放流菜单无感新增 4K"]
    end

    T1 --> AuditCheck
    AuditCheck -- 驳回 REJECTED --> CascadeCancel
    AuditCheck -- 通过 SUCCESS --> FinalDecision

    T2 --> BaseStreamCheck
    T3 --> BaseStreamCheck
    BaseStreamCheck -- 是 --> FinalDecision

    T5 --> VectorCheck
    VectorCheck -- 成功 SUCCESS --> FinalDecision

    FinalDecision -- 达成上线条件 --> AutoPublish
    T4 -.->|长耗时异步完成| SilentAppend
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
graph TD
    subgraph PipelineLifecycle ["任务生命周期流转"]
        TS_Pending["PENDING (待调度)"] -->|Worker接收任务| TS_Running["RUNNING (执行中)"]
        TS_Running -->|执行成功| TS_Success["SUCCESS (完成)"]
        TS_Running -->|重试耗尽| TS_Failed["FAILED (失败)"]
        TS_Running -->|机审违规熔断| TS_Canceled["CANCELED (已取消)"]
    end

    subgraph Resuscitation ["重新提审复苏机制"]
        TS_Failed -->|重置为 PENDING| TS_Pending
        TS_Canceled -->|重置为 PENDING| TS_Pending
        TS_Success -->|已成功切片复用无需重转| TS_Success
    end
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
