# 项目模块开发 TODO

## 认证模块 · auth-service

说明：[认证模块](modules/auth.md)。

### 账号注册

- [x] 校验注册信息并创建普通账号，不自动登录。
- [x] 保存密码哈希，不保存明文密码。
- [x] 创建账号时同时登记资料初始化通知。

### 登录与登录状态维护

- [x] 校验登录名、密码和账号状态，签发访问令牌与刷新凭据。
- [x] 使用一次性刷新凭据换取新令牌，并检查账号最新状态。
- [x] 条件完成刷新，拒绝重复刷新以及已被退出操作终止的轮换。
- [x] 退出当前刷新会话，并撤销本次提交的访问令牌；不代表撤销全部历史访问令牌。

### 身份验证与当前账号

- [x] 为网关验证访问令牌，返回身份摘要；不实时读取账号状态。
- [x] 查询当前账号摘要并检查账号最新状态。

### 账号创建通知

- [x] 后台发送账号创建通知，按发送确认更新结果并有限重试。
- [x] 在注册事务提交后触发快速发送（可选能力，默认关闭）。
- [x] 为新认证库已有正常普通账号生成资料初始化通知（内部定时任务，默认关闭且只预览）。

## 用户模块 · user-service

说明：[用户模块](modules/user.md)。

### 用户资料初始化

- [x] 接收账号创建事件，只为缺失资料建档，不覆盖或恢复已有资料。
- [x] 识别重复事件，在同一事务中保存消费记录和资料。
- [x] 临时消费失败有限重试，非法事件和耗尽重试的消息进入死信处理。

### 本人资料查询与编辑

- [x] 普通用户查询本人资料；缺失时提示等待，不因查询自动创建。
- [x] 普通用户首次保存或修改昵称、简介、城市和生日，支持显式清空字段。
- [x] 按资料版本拒绝过期修改，避免旧页面覆盖新内容。

### 公开资料展示

- [x] 已登录用户查询其他用户的公开摘要，隐藏不可用资料。
- [x] 批量查询公开摘要，保留请求顺序和重复项，并标记不可用项。
- [x] 过滤非可信来源的头像地址（仅展示已有地址，不含头像上传或替换）。

### 管理端资料维护

- [x] 管理员筛选并分页查询未删除资料。
- [x] 管理员按版本修改已有正常资料，不创建、启停或恢复资料。

### 用户关注与社交关系

- [x] 关注与取消关注创作者，支持幂等处理与自关拦截。
- [x] 采用 `follow_status`（0/1）软状态标记与覆盖索引支撑高频变动。
- [x] 独立 `user_counter` 计数快照表，通过原生原子自增/自减维护关注数与粉丝数，与资料表彻底解耦。
- [x] 双方社交关系智能判定（未关注、已关注、被关注、互相关注）。
- [x] 分页查询关注与粉丝列表，聚合公开资料与成为粉丝/关注时间。
- [x] 关注/取关成功后在事务提交后向 RabbitMQ 广播领域事件（`user.relation.followed.v1` / `user.relation.unfollowed.v1`）。
- [x] 暴露内部查询端点获取关注列表，赋能推荐服务关注召回通道。

## 文件模块 · file-service

说明：[文件模块](modules/file.md)。

### 普通上传

- [x] 普通用户上传单个私人文件，校验文件要求并保存对象。
- [x] 对象上传及文件记录保存明确成功后返回完成信息。

### 客户端直传与确认

- [x] 签发 V1 临时上传地址，并异步读取对象确认上传（兼容路径）。
- [x] 曾实现 V2 带摘要的暂存上传与异步确认接口；新上传入口已标记过期，默认关闭，不再作为接入目标。
- [x] V2 在确认时校验对象大小，并按源对象标识将暂存文件复制为正式文件。
- [x] 查询本人上传状态；区分正在确认与上传完成。
- [x] 停止将 V2 新上传入口作为后续开放目标：当前存储策略与 V2 checksum PUT 签名不兼容；保留已有 V2 记录的确认、恢复与清理能力。V1 仍为现有直传路径，不按 V2 切换规划停用。

### 下载、内部读取与删除

- [x] 为本人已完成、正常且未删除的文件签发短期下载地址。
- [x] 按调用方传入的已认证用户 ID 打开其已完成文件内容（仅进程内服务，无跨服务读取接口）。
- [x] 删除前先隐藏文件，远端对象删除明确成功后记录删除完成。
- [x] 重复删除已完成删除的本人文件，按成功处理。

### 未完成上传清理与失败恢复

- [x] 分批清理过期上传及其对象（内部任务，默认关闭）。
- [x] 恢复 V2 在途确认，并清理已完成文件的暂存残留（内部任务，默认关闭）。
- [x] 重试尚未完成的文件删除（内部任务，默认关闭，也可重复调用删除接口）。

## 网关模块 · gateway-service

说明：[网关模块](modules/gateway.md)。

### 请求转发

- [x] 按路径配置七个业务服务的转发入口，不代表下游业务均已实现。
- [x] 按白名单放行登录、注册、刷新和探针请求。

### 身份校验与传递

- [x] 拦截缺少或无效凭据的受保护请求，向认证服务验证身份。
- [x] 缓存验证结果（包括无效结果）；正常有效令牌的缓存不超过其剩余时间。
- [x] 清除客户端身份头，认证通过后重新注入身份。
- [x] 经网关注销后尝试删除本次令牌的验证缓存。

## 公共模块 · common-core / common-web

说明：[公共模块](modules/common.md)。

### 统一数据格式

- [x] 提供接口响应的统一外壳，业务数据仍由各服务定义。
- [x] 提供事件标识、版本和追踪信息的公共外壳，不包含具体业务校验。

### 请求上下文

- [x] 在 Servlet 请求中接收或生成追踪标识，关联日志并写入响应。
- [x] 将网关注入的身份转换为请求内上下文，不负责令牌验签。
- [x] 自动装配 Servlet 过滤器，并在请求结束后清理上下文。

## 内容模块 · content-service

说明：[内容模块](modules/content.md)。

### 领域模型与仓储持久化

- [x] 建立物理主键与 24 位高熵 Base62 业务短码（`vid`）双 ID 体系。
- [x] 视频聚合根状态机、转码流切片模型与标签引用热度增量同步。
- [x] 标签字典单层类型扩展（`DOMAIN` 泛化领域 vs `TOPIC` 具体主题）与分类检索能力。
- [x] 基于 MyBatis-Plus 的表结构映射与仓储层落地。

### 创作者工作台与播放分发

- [x] 创作者草稿箱新建、元数据更新、Feign 远程文件资产探活与提审发布。
- [x] 前台公开多清晰度切片播放流分发与按可见性策略安全脱敏。
- [x] 管理端作品多维检索与封禁/解封治理。

### 异步任务流水线与门禁

- [x] 视频提审分解为 5 类细粒度子任务（审核、基准转码、4K 转码、向量提取）。
- [x] 落地工业级分级就绪门禁（`PublishGatekeeper`）：审核通过 + 基准画质就绪 + 向量就绪即放行，4K 异步非阻塞追加。
- [x] 超时未完成任务自愈巡检与重试调度器（`VideoTaskTimeoutScheduler`）。
- [x] 事务性发件箱（`content_outbox`）完整动力系统：双通道投递（afterCommit 虚拟线程快速通道 + 定时自愈扫描）、CAS 租约原子防重、指数退避抖动与 MDC 全链路追踪。

## 审核模块 · audit-service

说明：[审核模块](modules/audit.md)。

### 领域模型与数据持久化

- [x] 建立审核任务（`AuditTask`）、多维度明细证据（`AuditDetail`）与敏感词字典（`AuditSensitiveWord`）聚合模型。
- [x] 完成对应数据表 DDL 定义与 MyBatis-Plus 仓储持久化落地。

### 自动化机审流水线与仲裁

- [x] 实现基于确定有限状态机（DFA）前缀树的高性能敏感词扫描引擎（`DfaTextAuditEngine`），支持分级拦截（`ILLEGAL` vs `SUSPICIOUS`）与热重载。
- [x] 实现多媒体封面规则审查引擎（`DefaultRuleImageAuditEngine`），支持测试桩模拟与云厂商可插拔扩展。
- [x] 实现基于安全最高优先级（`ILLEGAL` > `SUSPICIOUS` > `NORMAL`）的多维度判定仲裁决策器（`AuditDecisionAggregator`）。

### 提审事件消费与闭环回调

- [x] 监听 RabbitMQ `content.video.submitted` 提审事件，自动启动机审并建立证据日志。
- [x] 通过 OpenFeign（`ContentServiceClient`）回调内容服务内部端点 `POST /api/content/videos/internal/audit-callback`，驱动视频门禁流转。
- [x] 实现回调超时重试与状态对齐自愈调度器（`AuditCallbackRetryScheduler`）。
- [x] 提供内部提审演练调试接口与任务证据明细查询端点。

### 人工复审与词库治理（阶段二规划）

- [x] 管理端人工审核待办工单池分页检索与审批/驳回接口。
- [ ] 敏感词字典动态增删查接口。
- [ ] 对接阿里云内容安全等真实第三方云机审 SDK 适配器。

## 转码模块 · transcode-service

说明：[转码模块](modules/transcode.md)。

### 领域模型与仓储持久化

- [x] 建立转码工单聚合根（`TranscodeTask`）、画质规格预设（`QualityPreset`）与全状态机流转模型。
- [x] 完成对应数据表 DDL 定义（`transcode_task`，唯一键 `uk_video_quality_format`）与 MyBatis-Plus 仓储持久化落地。

### 执行引擎与硬件保护

- [x] 实现基于宿主机 FFmpeg/FFprobe 的音视频压制引擎（`FfmpegTranscodeEngine`），支持等比保真、黑边填充与 Web `faststart` 秒开优化。
- [x] 实现支持脱网运行与 CI 快速验证的模拟桩引擎（`MockTranscodeEngine`）。
- [x] 落地基于公平信号量的硬件并发保护限流器（`TranscodeRateLimiter`），支持 Java 21 虚拟线程调度。

### 事件驱动与跨微服务协同闭环

- [x] 声明 RabbitMQ 队列 `transcode-service.video-submitted.v1`，异步消费 `content.video.submitted` 提审事件。
- [x] 跨服务文件交互：Feign 申请 `file-service` 临时直链流式拉流，完成切片后通过受信任内部端点 `POST /api/files/internal/upload` 托管上传并签发资产 ID。
- [x] 发布门禁协同：通过 OpenFeign 回调 `content-service` 内部端点 `POST /api/content/videos/internal/transcode-callback` 登记流规格与视频时长，驱动 `PublishGatekeeper` 门禁流转。
- [x] 资源清理自愈：任务沙箱临时工作区在 `finally` 阶段强力递归清除，避免磁盘泄漏。
- [ ] HLS（`.m3u8` + `.ts`）分片转码（阶段二规划）。

## 互动模块 · interaction-service

说明：[互动模块](modules/interaction.md)。

### 基础入口

- [x] 提供服务启动入口与注册配置（工程骨架，集成 Redis、MySQL、MyBatis-Plus、RabbitMQ）。

### 点赞

- [x] 点赞与取消点赞，状态存在 `interaction_like`；重复请求幂等，不重复计数、不重复发事件。
- [ ] 分页查询本人点赞列表（尚无接口）。

### 收藏与收藏夹

- [x] 默认收藏夹在首次收藏或查询时自动创建。
- [x] 新建自定义收藏夹。
- [x] 收藏到指定或默认收藏夹、按收藏夹或全部取消；“首次收藏 / 彻底移除”才计数并发事件。
- [x] 分页查询收藏夹内视频。
- [x] 取消收藏后再收藏复活原明细，避免唯一键冲突。
- [ ] **【P0 越权风险】收藏夹属主校验**：`resolveFolder` 与 `getStarItems` 未校验 `folderId` 归属，传入他人收藏夹 ID 即可写入或读取其内容。修复前不得对外开放收藏接口。
- [ ] 收藏夹重命名、重名校验与删除（含明细处理）。

### 观看心跳与观看历史

- [x] 统一心跳入口：登录用户上报进度，服务端维护断点与累计时长；游客返回 `401`。
- [x] 会话隔离：30 分钟无心跳视为新会话，每会话最多一次有效播放。
- [x] 有效播放防重：会话满 5 秒计首次播放；再次播放需要上一会话达 30% 且超过 6 小时冷却；数据库双 CAS 抢占。
- [x] Redisson 分布式锁在事务外层，等锁超时只读降级。
- [x] 完播：播放头达 90% 时 CAS 置位并发送 `PLAY_COMPLETE`。
- [x] 播放量在事务提交后才计入 Redis。
- [x] 断点查询、播放页状态快照（点赞/收藏/断点/完播）。
- [x] 观看历史分页、删除单条与清空；逻辑删除保留防刷时间戳，删了重看不重置冷却。
- [ ] 防刷门禁：按服务端时间差校验增量、视频时长改用可信来源、完播需要观看时长佐证（待定时长来源）。
- [ ] 统一完播防重语义（待定：每个用户每个视频一次，还是按会话计）。

### 分享

- [x] 分享必须带 `Idempotency-Key`，`interaction_share_record` 持久化防重后计数并发事件。
- [ ] 幂等键冲突返回 `409`（当前为 `500`）。

### 公开计数

- [x] 单条与批量查询公开计数，缺失的补 0。
- [x] Redis Hash 写缓冲加脏集合，`VideoCounterFlushScheduler` 定时把快照刷进 `interaction_video_counter`；读写滑动续期。
- [ ] **【P0 数据覆盖风险】计数刷盘覆盖**：Redis 计数 Key 过期后，下一次 `HINCRBY` 生成只含单字段的 Hash，`upsertSnapshot` 以绝对值覆盖 `interaction_video_counter`，导致历史累计被清零或变小（`comment_count` 每次写 0）。修复前公开计数不可信。
- [ ] 刷盘失败后脏标记丢失；Redis 降级到本机内存后多实例计数分裂。
- [ ] 点赞、收藏、分享计数改为事务提交后写入（与播放量一致）。

### 领域事件与 Outbox

- [x] 统一事件 `interaction.video-action.v1`（`LIKE / STAR / PLAY / PLAY_COMPLETE / SHARE`），只记录真实状态变化。
- [x] 自属 `interaction_outbox`：同事务落库、租约抢占、Broker Confirm、有限重试；投递默认关闭。
- [ ] `recommend-service` 消费 `interaction.video-action.v1`（幂等），之后才打开 `dispatch-enabled`。
- [ ] Outbox 已发布记录的保留期清理。

### 后续规划

- [ ] 树形评论与楼中楼（接入机审与多级排序）。
- [ ] 确定公开查询接口是否对游客开放（需要调整网关白名单）。

> 已移除旧条目：`InteractionRedisBuffer` / `MetricsDeltaFlushScheduler` / content `metrics-delta` 回写、`session-finish` 结账端点与 `interaction.user.session-feedback` 事件。代码中不存在这些实现，并且与“计数由互动服务独占、不回写 content”的现行边界冲突。

## 推荐模块 · recommend-service

说明：[推荐模块](modules/recommend.md)。

### 基础入口

- [x] 提供服务启动入口与注册配置（工程骨架，包含 MyBatis-Plus、MySQL、Redis、RabbitMQ、OpenFeign、Qdrant 向量库集成）。

### 视频特征向量化与内容门禁闭环

- [x] 监听 RabbitMQ `content.video.submitted` 提审事件，在 Java 21 虚拟线程中异步计算视频高维特征向量。
- [x] 设计双模向量引擎（优先标准通用 OpenAI 兼容协议，网络抖动或未配 Key 时自动降级为本地确定性 Feature Hashing 算法）。
- [x] 对接 Qdrant 向量数据库（REST :6333），自动建立 `video_vectors` 集合（Cosine 距离），持久化 Point 并注入业务 Payload。
- [x] 建立自属表 `recommend_video_vector`，按视频幂等记录向量与处理状态（当前无 Redis 向量缓存）。
- [x] 通过 OpenFeign 客户端回调 `content-service` 的 `/api/content/videos/internal/task-callback` 接口，汇报 `VECTOR_EMBEDDING` 为 `SUCCESS`，打通平台视频发布门禁全链路。

### 推荐候选池库存与生命周期闭环

- [x] 建立自属推荐候选池轻量元数据表 `recommend_candidate_video`，负责维护作者打散维度、领域/主题标签属性及推荐可用状态。
- [x] 监听 RabbitMQ `content.video.published` 发布上线事件，以强幂等方式将新作品正式准入推荐候选库存池（`status=ACTIVE`）。
- [x] 监听 RabbitMQ `content.video.offlined` 与 `content.video.banned` 生命周期事件，将候选状态变更为 `OFFLINE` 或 `BANNED`，实现合规清退与熔断下线。
- [ ] **【REC-01 契约不一致】**内容服务下架发送 `content.video.offline`，推荐侧绑定 `content.video.offlined`，创作者下架的视频不会出池。
- [ ] **【REC-05】**消费 `content.video.unbanned`，解封后恢复 `ACTIVE`。

### 用户模型与行为反馈事实闭环

- [x] 建立自属用户画像状态快照表 `recommend_user_profile`，维护即时检索向量、细主题偏好快照、粗领域状态快照、近期观看短码序列与乐观锁版本号。
- [x] 建立自属用户明确屏蔽约束表 `recommend_user_block`，维护拉黑视频、作者、主题标签的硬过滤规则与 O(1) 判定。
- [x] 建立自属原始行为反馈事实流水表 `recommend_feedback_log`，只追加记录有效曝光、播放消费、滑过跳过与负反馈客观事实。
- [x] 领域层实现 `UserProfile` 聚合根、`UserVector` 值对象（封装增量指数移动平均 EMA 与 L2 归一化）、`UserBlock` 实体及完整持久化仓储实现。
- [x] 行为反馈接口 `POST /api/recommend/feedback`：记流水，有效播放推进画像，快速滑过抑制粗领域，负反馈自动屏蔽。
- [x] 用户屏蔽接口 `GET / POST / DELETE /api/recommend/blocks`（视频 / 作者 / 主题）。
- [ ] 反馈时长校验：播放时长与视频时长当前完全信任客户端（REC-04，待决策）。

### 首页推荐流

- [x] `GET /api/recommend/feed`：四路并发召回（个性化 50% / 探索 30% / 热度 10% / 关注 10%），500ms 全局超时，单通道异常降级为空。
- [x] 四道硬过滤（状态、本人作品、屏蔽、近期已看）、槽位交织、冷启动补齐、同作者间隔 >= 2 打散。
- [x] Redis 待看缓冲队列：大包预生成、低水位异步补水、Redis 异常回退实时计算。
- [ ] 关注召回：`FollowingRecallChannel` 当前恒返回空，需接入 `user-service` 内部关注清单接口（含超时与降级）。
- [ ] 消费 `interaction.video-action.v1`（幂等），热度召回接入互动数据。
- [ ] 缓冲队列弹出时复核屏蔽与候选状态（REC-02）。
- [ ] 参数校验与未登录错误映射为 `400 / 401`（REC-03，当前推断为 `500`）。
- [ ] 确定游客是否可访问推荐流（需调整网关白名单）。
- [ ] 相关推荐 `GET /api/recommend/videos/{vid}/related`（规划）。
- [ ] 消费失败死信队列与告警（当前非法消息直接丢弃）。


