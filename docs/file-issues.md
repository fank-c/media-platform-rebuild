# 文件模块 · 独立问题清单

- 核对日期：2026-09-09。
- 关联功能：[文件模块](modules/file.md)、[模块功能计划](TODO.md)。
- 状态：F-01 已按 V2 代码与 Schema 实施；真实 MinIO/OSS checksum、CORS、条件 copy 和 MySQL 交错验收仍未完成。

本文用于实现后的逐项讨论，不替代功能文档，也不是新的完整执行方案。问题关闭需要说明修复位置、实际验证和剩余限制，不能只因已有相关代码就标为解决。

## 一、先处理哪些问题

| 编号 | 关联功能 | 问题 | 当前结论 |
| --- | --- | --- | --- |
| F-01 | Part 2 直传确认 | 完成后的对象没有固定到不可改写的内容 | V2 已改为 staging checksum PUT + permanent 条件 copy；V1 兼容路径和真实存储验证仍未关闭 |
| F-02 | Part 3 删除 | 删除对象与写逻辑删除之间有竞争窗口 | 删除闸门与恢复已修复；待真实 MySQL/MinIO 联调确认 |
| F-03 | Part 4 清理 | EXPIRED 每轮从头扫描，尾部可能一直轮不到 | 已有重试扫描，但游标仅存在于单轮，需修正公平性 |
| F-04 | Part 1 上传 | 上传结果不确定和孤立对象缺少确认恢复流程 | 当前返回失败/保留对象，不盲删，但没有完整补救 |
| F-05 | Part 3 下载 | 签名发放鉴权不等于每次下载鉴权 | 契约限制，不能承诺链接即时撤销 |
| F-06 | 全部 | 存储直连和可信身份依赖部署边界 | 代码不等于网络隔离证据，尚待实际验证 |
| F-07 | 内部读取 | 进程内服务不等于跨服务读取接口 | 当前明确为进程内能力，下游接入另议 |
| F-08 | 业务接入 | 私有文件尚无公开访问和引用保护 | 不应直接当作完整头像/视频资产能力 |
| F-09 | Part 2、4 | 总预算不等于底层 I/O 硬截止 | 有步骤检查与 SDK 超时，仍需验证资源占用边界 |
| F-10 | Part 1、2 | 扩展名规则、MIME 校验、可重开上传源未完整落实 | 与原始需求有差异，需明确补齐范围 |
| F-11 | Part 2 | 内容不合法调用的过期 SQL 只接受已到期记录 | 已核实状态转换缺口，优先处理 |
| F-12 | 全部 | 测试证据未覆盖主要异步和持久化路径 | 当前仅定位到三份测试源码；本轮未执行 |

建议先完成 **F-01 的真实 MinIO/OSS POC 和隔离验收**，再处理 **F-03、F-04** 的清理公平性与孤立对象补救；面向不可信客户端开放 V2 直传前，仍必须完成 **F-05、F-06** 的部署边界确认。其他项随独立验收与首个业务调用方接入逐步推进，不自动扩展本轮范围。

## 二、独立问题

### F-01：校验完成后，对象内容仍可能变化（V2 已实施，待真实依赖验收）

- **原问题**：V1 直传 PUT 直接写最终 key，旧 PUT URL 在有效期内可覆盖已确认对象；前后 HEAD 不能锁住完成更新后的对象内容。
- **实现证据**：V2 新增 `DIRECT_STAGED_CHECKSUM_V2` 协议、`VERIFYING` 状态和 `expected_sha256`。初始化只向 `staging/{yyyy}/{MM}/{dd}/{fileId}` 签发带 `Content-Type` 与 `x-amz-checksum-sha256` 的 PUT 签名；`storage_key` 固定为 `permanent/{yyyy}/{MM}/{dd}/{fileId}`。确认先 CAS 抢占 `VERIFYING`，再 HEAD staging、记录 ETag、以 ETag 条件 server-side copy，最后用完成 CAS 写入 `sha256`。
- **安全结果**：完成后的永久对象不再由 V2 PUT URL 直接写入；迟到 PUT 只能影响 staging，不能越过数据库状态机重新完成文件。若确认与删除/过期交错导致完成 CAS 失败，任务会补删可能生成的两个对象。
- **兼容边界**：V1 仍保留兼容窗口，不能把 V1 标记为已修复。V2 默认关闭，必须先完成真实 MinIO/目标 OSS checksum POC，再由外部调用方迁移并灰度开启。
- **关闭验证**：真实验证正确/错误 checksum、缺失/篡改签名头、重复 PUT、HEAD 后替换源对象的条件 copy、完成后旧 PUT URL 不影响 permanent、浏览器 CORS、最小权限、staging 生命周期及多实例交错。当前仅有代码、Schema、静态检查和测试编译证据。

### F-02：远端删除与逻辑删除不是一步完成（修复完成，待真实依赖联调）

- **实现证据**：`file_asset.delete_requested_at` 是独立删除闸门；`FileAssetApplicationService.delete` 先通过 `requestDeletion` CAS 建立闸门，再删除行内对象并通过 `completeDeletion` CAS 写 `deleted_at`。`FileAssetMapper` 的可见查询、确认完成 CAS 和普通上传清理均排除删除中的记录；`FileCleanupService.recoverDeletion` 独立扫描未收敛删除并补写墓碑。删除恢复与上传清理均受默认关闭的 `file.cleanup.enabled` 控制。
- **行为结果**：删除意图一旦写入，新的查询、下载签名、confirm 和进程内内容读取统一按 `404` 隐藏；在途 confirm 即使已经读取对象，其完成 CAS 也会更新 0 行。远端明确不存在视为删除完成；远端结果未知或最终墓碑写入未确认保持删除中并由 DELETE/恢复任务重试，HTTP 返回 `503`，不把“正在删”误报为 `204`。
- **数据库状态**：当前环境尚未创建真实数据库，`file_asset` 的完整结构已直接合并到 [服务 Schema](../service/file-service/db/schema/file-asset.sql) 和根初始化脚本；本次不执行数据库操作，也不保留面向旧表的增量 `ALTER TABLE` 脚本。未来已有数据库需要升级时，应单独设计并审查前向迁移与回退方案。
- **仍未覆盖**：已打开的读取流不会被强制中断；已签发 GET/PUT URL 不会即时撤销。迟到 PUT 不能通过 confirm 重新完成文件，但同 key PUT 在 URL 有效期内仍可能暂时产生孤立对象。真实 MySQL CAS、MinIO 删除、网络中断和多实例并发顺序尚未在本轮运行验证。
- **已执行测试**：应用层测试覆盖闸门先行、远端未知结果、最终墓碑未确认、重复 DELETE 和删除恢复；Mapper 注解 SQL 静态测试覆盖可见查询、确认 CAS、墓碑 CAS 的闸门条件。
### F-03：清理已有重试，但后面的记录可能长期得不到处理

- **现状证据**：`FileCleanupService.cleanup` 每次都从 `expiredCursor = null` 开始；受 `maxBatches` 和时间预算限制。`FileAssetMapper.selectExpired` 只按 ID 分页，成功删除后仍保留 EXPIRED，未记录已清理状态。
- **实际影响**：假设每轮最多扫描 500 条，而前 500 条一直保留 EXPIRED，每轮都会重复处理它们，第 501 条可能永远轮不到；前半段 PENDING 清理耗尽预算也可能让 EXPIRED 扫描没有机会执行。
- **已有措施**：单轮内确实按游标推进；对象存储分类异常不会中断整批；EXPIRED 会再次扫描。不能再描述成“完全没有重试”。
- **待讨论方向**：跨轮保存并回绕游标、给两类扫描独立预算，或增加清理进度与重试时间。评估多实例、重启和迟到上传后再选最小改法。
- **关闭验证**：记录数超过一轮上限，连续多轮后尾部必须被处理；首批持续失败也不饿死后续记录；重启和迟到对象可收敛。

### F-04：上传失败不盲删是对的，但恢复依据还不够

- **现状证据**：`FileAssetApplicationService.upload` 直接 PUT，随后 INSERT。对象存储异常返回失败，没有在结果未知时重新读取并核对长度/摘要；数据库失败也没有完整查证或持久化补偿入口。
- **实际影响**：远端可能成功但响应丢失；或者远端成功而数据库保存失败。客户端收到失败，桶里仍有对象。仅扫描 file_asset 找不到没有元数据的对象。
- **已有措施**：不把对象存储放进数据库事务，不在数据库结果未知时盲删对象，不假报上传成功。
- **待讨论方向**：补有界查证及明确的人工对账入口，再决定是否需要上传尝试记录、孤立对象清理依据。DB 抛异常不等于一定未提交，不能查一次没看到就立即删对象。
- **关闭验证**：PUT 响应丢失、DB 明确失败/提交未知、查证失败、进程中断与补救删除失败，分别验证返回结果和保留/清理依据。

### F-05：下载链接的授权与撤销边界

- **现状证据**：`downloadUrl` 先检查本人归属与可用状态，再 HEAD 并生成短期 GET 签名；读取对象内容时没有经过该业务方法再次认证。
- **实际影响**：不能把“只有本人可以申请”写成“任何时候都只有本人可以下载”；签名被转发、文件被禁用或用户注销后旧链接是否继续可用，需要按实际存储与 TTL 验证。
- **待讨论方向**：明确接受短期持有者访问窗口，还是敏感读取必须每次经受控接口；不记录完整签名 URL。
- **关闭验证**：转发、过期、禁用/注销后的行为与文档一致，不承诺未经验证的即时撤销。

### F-06：网关规则已有例外，但还需要部署证据

- **现状证据**：当前 AGENTS.md 已规定业务 API 经网关，MinIO PUT/GET 只能作为 ADR 与部署确认的预签名例外。业务身份依赖网关传递的可信上下文，而不是任意请求中的 ownerId。
- **实际影响**：若客户端能直接访问业务服务并伪造身份 Header，文件所有权检查也无法弥补入口不可信。存储签名域名、代理和 CORS 不匹配则会导致浏览器上传失败。
- **待讨论方向**：对实际运行入口验证服务隔离、私有 bucket、签名端点、必要请求头和 CORS，不自动开放端口或改变共享环境。
- **文档限制**：当前工作区正整理文档，旧 `docs/adr/`、架构和契约路径显示删除；本轮不恢复或覆盖这些用户改动。后续正式决策需明确新的承接位置，不能引用不存在的 ADR 当作已批准证据。
- **关闭验证**：真实网关防伪造、业务服务不可外部绕过、匿名桶访问被拒、浏览器签名 PUT/GET 成功。

### F-07：内容读取目前仅供本进程使用

- **现状证据**：`FileContentService.openForProcessing` 返回 `FileContentResource`，先检查归属与完成状态；类注释明确不是跨 JVM API。
- **实际影响**：content/user 等独立服务不能直接注入它，传入 actorUserId 也不能独立证明调用方有委托权限。
- **待讨论方向**：出现首个真实调用方后再定义受控服务间读取协议、身份传递、超时、取消和流关闭。不共享 Mapper/实体，不提前建设通用平台。
- **关闭验证**：具体调用方的合法读取、伪造主体拒绝、远端中断与资源释放。

### F-08：文件所有权尚未覆盖业务引用

- **现状证据**：文件查询和删除依据 create_by、状态与 deleted_at，没有内容发布/头像引用保护流程。
- **实际影响**：私人文件如果直接被投稿引用，用户删除它可能使投稿失去源内容；只有所有者可读也不能满足公开头像或视频观看。
- **待讨论方向**：由业务服务决定公开权限；文件服务提供归属与可用事实。接入时明确引用建立/释放和“被引用文件是否允许删除”。
- **关闭验证**：越权引用、被引用删除、公开/撤回、业务操作失败后的引用恢复。

### F-09：超时配置不能直接当作任务硬截止保证

- **现状证据**：确认任务在关键步骤检查预算，但摘要循环没有按读取块检查 deadline；清理任务在对象调用前检查预算，未把剩余预算传入 delete。`MinioStorageConfiguration` 配置 SDK 连接、读取和调用超时。
- **实际影响**：总任务或整轮预算已耗尽时，某次底层 I/O 仍可能占用线程。队列有界能控制排队数量，不能独自保证每项工作按总预算退出。
- **待讨论方向**：根据实际 SDK 取消/关闭能力收敛剩余预算和读取中断，先验证慢速存储场景，不直接宣称所有超时均失效。
- **关闭验证**：慢 GET/DELETE、持续小块数据、总期限结束、线程占用恢复与流关闭；区分 SDK 单次超时和用例总期限。

### F-10：原始输入与存储端口要求还存在差异

- **现状证据**：上传路径检查文件名长度、路径字符、大小等，但未定位到扩展名允许列表；确认读取 HEAD 的大小和对象标识，没有校验 contentType；`ObjectStorageClient.put` 接受单次 InputStream，而不是可重新打开的内容源。
- **实际影响**：不能宣称不允许的扩展名已被拒绝、MIME 已在确认时核对，或上传重试已经能够安全重放流。
- **待讨论方向**：补齐实际需要的扩展名配置和两种上传共用校验；明确 MIME 只是声明/元数据核对，不是内容鉴定。若引入重试，先完成内容源重开及关闭职责，不复用消耗过的流。
- **关闭验证**：非法扩展名、大小写与无扩展名、MIME 不一致、重开流与失败关闭、配置绑定。是否调整原始要求必须明确记录，不能静默省略。

### F-11：无效内容无法在截止时间之前进入 EXPIRED（V1 已修复）

- **历史问题**：V1 直传确认发现大小或对象观测不一致时调用 `expirePending(id, now)`；但该 SQL 只接受 `upload_expires_at <= now`，导致截止前的错误内容仍停留在 `PENDING`。
- **本次修复**：新增 V1 专用 `rejectPendingContent(id, now)`，独立于自然到期的 `expirePending`。它只更新未删除、`upload_protocol='LEGACY_V1'` 且仍为 `PENDING` 的记录，不要求上传期限已到。
- **状态边界**：首次 HEAD 大小错误、流式读取后的对象替换、大小变化或实际读取大小不符，会立即作废；队列拒绝、确认预算耗尽、对象存储异常和流读取 I/O 异常仍保留 `PENDING`，允许后续重试。
- **并发保护**：作废 SQL 检查影响行数。返回 `1` 表示本次确认成功作废；返回 `0` 表示记录可能已经完成、自然到期、删除或被其他确认任务作废，当前任务不再继续写状态。
- **关闭验证**：已补充 V1 确认行为和 Mapper SQL 条件测试；仍需在真实 MySQL/对象存储环境补做并发与故障场景验证。

### F-12：主要路径仍缺少可复述的验证证据

- **已定位测试源码**：`FileAssetApplicationServiceTest`、`BoundedDigestInputStreamTest`、`FileStoragePropertiesTest`。
- **当前边界**：这不表示测试已通过，也不证明异步确认、清理扫描、MySQL 条件 SQL、厂商异常转换和真实签名行为已经验收。
- **待补验证**：确认队列拒绝/重复/技术故障、清理多轮公平性、删除并发、内容资源关闭、真实 MySQL 映射与状态竞争、真实 MinIO 签名和一致性故障场景。
- **关闭条件**：报告精确命令、JDK、通过/失败/跳过数和依赖环境；模拟存储与真实厂商结果分开。本轮只有源码与文档静态核对。

## 三、代码导航

以下是本轮核对入口；引用只证明代码位置，不表示对应问题已修复。

- [上传、下载和删除用例](../service/file-service/src/main/java/com/calles/platform/file/application/asset/FileAssetApplicationService.java)
- [直传确认服务](../service/file-service/src/main/java/com/calles/platform/file/application/confirmation/DirectUploadConfirmationService.java)
- [分批清理服务](../service/file-service/src/main/java/com/calles/platform/file/application/cleanup/FileCleanupService.java)
- [状态条件 SQL](../service/file-service/src/main/java/com/calles/platform/file/infrastructure/persistence/FileAssetMapper.java)
- [内容读取服务](../service/file-service/src/main/java/com/calles/platform/file/application/content/FileContentService.java)
- [存储端口](../service/file-service/src/main/java/com/calles/platform/file/application/port/ObjectStorageClient.java)
- [MinIO 配置](../service/file-service/src/main/java/com/calles/platform/file/config/MinioStorageConfiguration.java)
- [文件模块测试目录](../service/file-service/src/test/java/com/calles/platform/file/)

后续每次只选择一个问题讨论、出方案、审查、实施与验收；不要将本清单全部当作一次实施授权。
