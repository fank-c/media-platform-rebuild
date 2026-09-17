# 服务调用主线 05：平台合规治理、违规封禁与全站事件广播下线

本文档梳理运营与安全管理员在管理端后台**检索可疑作品、执行违规封禁（Ban）与解封（Unban）、状态原子跃迁以及通过领域事件广播驱动全站搜索、推荐与前台即时下线**的端到端服务调用全流程。

---

## 1. 参与组件与调用拓扑

```mermaid
flowchart TB
    Admin(["安全管理员"])
    GW["API Gateway<br/>gateway-service:8000"]
    CS["内容服务<br/>content-service:8030"]
    MQ[["RabbitMQ 领域事件总线"]]
    Search[["搜索引擎 ElasticSearch"]]
    Rec["推荐系统<br/>recommend-service"]
    Notice[["消息与通知中心"]]

    %% 管理操作
    Admin -->|1. 提交封禁请求 POST admin/ban| GW
    GW -->|RBAC 鉴权严格校验 ADMIN 角色| CS
    
    %% 本地事务与发件箱
    CS -->|2. 原子置为 DISABLED 并写 Outbox| CS
    
    %% 异步广播全站
    CS -.->|3. 广播封禁事件 content.video.banned| MQ
    MQ -.->|清除搜索快照与倒排索引| Search
    MQ -.->|拉黑视频并停止推荐流分发| Rec
    MQ -.->|站内信通知违规作者与申诉指引| Notice
```

---

## 2. 端到端执行时序图 (End-to-End Sequence)

```mermaid
sequenceDiagram
    autonumber
    participant Admin as 管理员
    participant GW as gateway-service
    participant CS as content-service
    participant DB as MySQL (video_content)
    participant MQ as RabbitMQ
    participant Rec as 推荐与搜索下游

    %% 第一步：管理员发起封禁
    rect rgb(240, 248, 255)
    Note over Admin,Rec: 步骤一 管理端 RBAC 鉴权与封禁指令安全下发
    Admin->>GW: POST /api/content/videos/admin/{id}/ban (JSON: reason)
    Note over GW: 提取 Token 校验角色是否为 ADMIN
    alt 角色非管理员
        GW-->>Admin: HTTP 403 FORBIDDEN 拒绝越权访问
    else 鉴权通过
        GW->>CS: 转发请求 (注入受信头 X-User-Role: ADMIN)
    end
    end

    %% 第二步：状态原子跃迁与事务发件箱
    rect rgb(255, 250, 240)
    Note over Admin,Rec: 步骤二 本地事务原子更新与发件箱强一致持久化
    CS->>DB: 开启本地数据库事务
    CS->>DB: UPDATE video_content SET status = 'DISABLED', reject_reason = #{reason} WHERE id = #{id}
    CS->>DB: 写入 content_outbox 记录 (event_type = 'content.video.banned')
    CS->>DB: 提交事务 (保障业务状态与事件派发的强一致性)
    CS-->>GW: 返回 200 OK (操作成功)
    GW-->>Admin: 返回封禁生效响应
    end

    %% 第三步：异步领域事件全站下线
    rect rgb(240, 255, 240)
    Note over Admin,Rec: 步骤三 全站各业务线协同下线闭环
    CS->>MQ: 投递事件 content.video.banned (包含 videoId, vid, authorId, reason)
    MQ->>Rec: 推荐与搜索引擎异步消费封禁事件
    Rec->>Rec: 1. 推荐系统：将该视频移出候选召回池与热门池<br/>2. 搜索引擎：物理抹除该视频的倒排索引快照
    Note over CS: 前台针对该 vid 的任何后续播放请求<br/>均因 status=DISABLED 直接返回 404 NOT_FOUND
    end
```

---

## 3. 核心机制深度剖析

### 3.1 状态分层解耦体系
平台的视频实体具备两套独立的状态维度，互不干扰：
1. **统一可用状态 `CommonStatus`**（平台级）：
   - `ACTIVE`：正常可用；
   - `DISABLED`：违规封禁/冻结。
2. **创作流转状态 `PublishStatus`**（业务生命周期）：
   - `DRAFT` / `AUDITING` / `PUBLISHED` / `REJECTED` / `OFFLINE`。

**封禁的工程优势**：管理员封禁仅将 `status` 翻转为 `DISABLED`，**保留了原有的 `publish_status`（如 PUBLISHED）**：
- **前台立即生效**：前台公众接口在可见性核验时发现 `status = DISABLED`，立即可见性阻断（安全脱敏为 404）；
- **低成本解封恢复（Unban）**：如果未来管理员经申诉核实后执行“解封”，只需将 `status` 重新置回 `ACTIVE`，视频**无需重新经历漫长的高耗能转码与机审流水线**即可直接恢复播放，极大降低了纠偏成本。

### 3.2 治理相关事件总线清单

| 事件类型 (`event_type`) | 触发时机 | 载荷核心字段 | 下游响应动作 |
| :--- | :--- | :--- | :--- |
| `content.video.banned` | 管理员封禁视频 | `videoId`, `vid`, `authorId`, `reason` | 推荐池拉黑、搜索引擎下架、创作者站内违规警告通知。 |
| `content.video.unbanned` | 管理员解封恢复视频 | `videoId`, `vid`, `authorId` | 重新激活搜索索引、恢复推荐分发通道。 |
| `content.video.offlined` | 创作者主动下架视频 | `videoId`, `vid`, `authorId` | 搜索与推荐池下线，前台隐藏。 |

> 💡 **模块细查**：
> - 管理端接口契约与权限校验见 [内容模块 · content-service](../modules/content.md#2-第一套件http-接口服务链路)。
> - 审核证据沉淀与工单详情见 [审核模块 · audit-service](../modules/audit.md#2-第一套件http-接口服务链路)。
