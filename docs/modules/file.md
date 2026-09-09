# 文件模块 · file-service

文件模块保存私人文件记录，并协调 MinIO 对象的上传、确认、下载与删除。业务 API 都通过网关访问，只允许普通用户操作自己的文件；管理员不会自动获得他人文件权限。客户端只在取得短期签名后直接向对象存储 PUT/GET，签名地址应作为敏感临时凭据，不写入日志。

当前有普通上传、V1 直传兼容路径和 V2 暂存直传。V2 新上传入口默认关闭；自动清理也默认关闭。下文描述当前工作区实现，不代表已在真实存储中启用或验收。当前注册的存储实现为 MinIO，不因存在存储接口就宣称其他对象存储已经适配。

## Part 1：普通上传

### 通过服务上传单个文件

调用 `POST /api/files`，请求类型为 `multipart/form-data`，文件字段名为 `file`，可选字段 `storageType` 当前使用 `MINIO`。需携带普通用户访问令牌，客户端不能指定 bucket 或对象 key。

服务先拒绝空文件、超限大小及含路径分隔符或控制字符的文件名，再生成文件 ID 和正式对象 key。随后流式上传对象，同时计算 SHA-256 和实际字节数；实际大小与声明一致、对象写入明确成功后，才保存 `COMPLETED` 文件记录。对象请求不放在数据库事务里。

成功为 HTTP `201`，`data` 返回 `fileId`、展示名、MIME、大小、摘要和状态等元数据；调用方可以保存文件 ID，后续凭它查询或申请下载地址。响应不单独暴露 bucket、对象 key 等内部元数据，但预签名 URL 本身仍可能包含存储路径，不能宣称地址完全隐藏对象位置。

默认应用大小上限为 `file.max-size=20971520` 字节；Servlet 文件和请求上限分别为 20MB、21MB。MIME 来自请求声明，缺省为 `application/octet-stream`，不是文件内容鉴定或病毒扫描结果。当前没有扩展名允许列表，确认过程也未核对 HEAD 返回的 MIME 与声明是否一致。参数错误为 `400`，超过应用上限为 `413`，存储不可用通常为 `503`。

如果对象已上传而数据库保存未获确认，接口不会返回成功，也不会盲目删除远端对象。当前没有覆盖所有这类孤立对象的自动恢复机制：后台只扫描数据库已知记录，不能把全桶孤儿回收写成已有能力。

源码入口：[文件 HTTP 接口](../../service/file-service/src/main/java/com/calles/platform/file/interfaces/http/FileController.java)、[上传与文件用例](../../service/file-service/src/main/java/com/calles/platform/file/application/asset/FileAssetApplicationService.java)。

## Part 2：客户端直传与确认

### 先申请上传，再确认业务完成

直传把文件正文从业务服务移到对象存储传输，但文件记录仍由文件服务管理。初始化成功只代表创建了待上传记录和临时授权，不代表文件已上传或可下载。

| 步骤 | V1 兼容入口 | V2 入口 |
| --- | --- | --- |
| 初始化 | `POST /api/files/direct-upload` | `POST /api/files/direct-upload/v2` |
| 确认 | `POST /api/files/{id}/confirm` | `POST /api/files/{id}/confirm/v2` |
| 查看状态 | `GET /api/files/{id}` | 同左 |

初始化 JSON 提交 `originName`、正整数字节数 `size`，可选 `mime`、`storageType`。V2 还必须提交文件内容的 `sha256`，为 64 位小写十六进制。客户端应先计算真实文件摘要，不使用任意固定占位摘要上传。

初始化返回 HTTP `201`，包含 `fileId`、`uploadStatus=PENDING`、`putUrl`、`requiredHeaders`、`putExpiresAt` 和 `uploadExpiresAt`。客户端下一步向 `putUrl` 发送文件正文，并原样携带 `requiredHeaders`，PUT 成功后再调用对应确认接口。签名有效期与业务确认期限是两个不同时间：默认分别为 `file.put-ttl=5m`、`file.upload-ttl=15m`。

初始化先落库，再签名；若签名生成失败，可能已经留下 `PENDING` 记录。当前没有重新签发原记录 PUT 地址的接口，也不能把失败后重新初始化当作幂等返回同一个文件 ID。

### V1：读取对象后计算摘要

V1 直接向旧式 `assets/日期/文件ID` 位置签发 PUT。确认请求在本进程去重后进入有限队列，后台先读取对象大小和标识，再流式读取正文计算摘要，最后再次读取对象信息；大小或前后对象标识不一致时，不标记完成。

首次确认及本进程仍在处理的重复请求返回 HTTP `202`，其中状态仍可能为 `PENDING`。后台确认成功后再次调用返回 `200` 与完成元数据。V1 的在途去重是进程内能力，不能写成跨实例只执行一次。确认已经证明对象大小不匹配、对象被替换或实际读取大小不符时，V1 使用独立的内容校验失败作废 SQL，截止时间之前也会立即进入 `EXPIRED`；队列拒绝、预算耗尽、对象存储异常和流读取 I/O 异常仍保留 `PENDING`，允许后续重试。

V1 仍保留旧 PUT 地址的兼容行为；前后检查不能保证完成后对象在剩余 PUT 有效期内绝不再被覆盖。因此不能把 V2 的正式对象固定机制归到 V1 上。

### V2：暂存文件确认后复制到正式位置

V2 初始化只有 `file.direct-upload-v2.enabled=true` 才开放，否则返回 `404`。启动校验同时要求 `file.cleanup.enabled=true`，避免留下无人恢复的在途状态。确认代码并不以这个开关拒绝已有 V2 记录，因此关闭新上传不等于停止所有旧记录处理。

客户端只能取得暂存位置的 PUT 签名，服务将声明摘要转换为 Base64 的 `x-amz-checksum-sha256`，与 `Content-Type` 一起纳入签名请求头。默认位置分别为 `staging/日期/文件ID` 与 `permanent/日期/文件ID`，前缀可配置，日期使用 UTC；已有对象不会因新前缀而自动迁移。

确认按以下顺序进行：

1. 检查文件归属、可见性和确认期限。数据库带条件地把 `PENDING` 改成 `VERIFYING`，表示已开始确认，尚不可下载。
2. 后台只读取暂存对象的头信息（HEAD），检查大小并记录 ETag。ETag 是对象存储提供的源对象标识，这里不能把它当作 SHA-256。
3. 要求存储在源 ETag 仍匹配时将暂存对象复制到正式位置。文件服务不下载完整暂存正文重新计算摘要。
4. 数据库再次按归属、状态和对象信息检查，更新成功才标记 `COMPLETED`，保存实际大小与初始化声明的摘要。
5. 尝试清理暂存对象。清理失败不回滚已经完成的文件，留给后续补偿扫描。

摘要可信依赖目标存储确实校验带签名的 checksum PUT；代码构造了这些请求，不代表本次已证明真实 MinIO/OSS 会按要求拒绝错误摘要。V2 开放前仍需隔离验证错误摘要、缺失或篡改签名头、ETag 条件复制以及浏览器跨域请求。

### 如何判断结果、重试和处理失败

- 确认返回 `202` 只是受理；使用响应的 `pollAfterSeconds`（当前为 2）等待后查询状态或再次确认。只有 `COMPLETED` 才表示上传业务完成。GET 只读元数据，不替调用方触发确认。
- 队列拒绝返回 `503` 和 `Retry-After: 2`。V1 保留 `PENDING`；V2 可能已经进入 `VERIFYING`，后续确认或恢复任务可以继续。
- 未到期但暂存对象不存在或外部请求失败，V2 可保持 `VERIFYING`；大小错误或条件复制内容冲突会拒绝本次上传并转为 `EXPIRED`。到期后确认返回 `410`，需要重新初始化上传。
- 复制成功但数据库完成更新未成功，不能返回已完成；可能保留 `VERIFYING` 供恢复。若记录已转入删除或过期分支，代码会尝试补删复制产生的对象，实际并发收敛仍需联调。
- 他人文件、不存在、删除中或已删除的文件均不能通过普通查询访问，返回 `404`。V2 专用确认收到非 V2 记录返回 `409`。

现有兼容规划是先开放并迁移调用方到 V2，再停止 V1 新初始化，等待旧 PUT 授权到期并处理在途文件，最后收敛 V1 确认；当前没有执行下线，也不能因仓库没有前端就假设没有外部调用方。

源码入口：[异步确认](../../service/file-service/src/main/java/com/calles/platform/file/application/confirmation/DirectUploadConfirmationService.java)、[MinIO 签名与条件复制](../../service/file-service/src/main/java/com/calles/platform/file/infrastructure/storage/MinioObjectStorageClient.java)、[文件表](../../service/file-service/db/schema/file-asset.sql)。当前环境尚未初始化数据库，完整 `file_asset` 结构直接由 Schema 定义创建；本次未执行任何数据库操作。

## Part 3：下载、内部读取与删除

### 获取私人文件的下载地址

调用 `GET /api/files/{id}/download-url`。服务先查本人可见记录，要求文件正常且上传完成；`PENDING / VERIFYING` 返回 `409`，正常状态下的 `EXPIRED` 返回 `410`。随后 HEAD 正式对象，确认对象存在且大小与记录相符，再生成短期 GET 地址。

成功响应包含 `url` 和 `expiresAt`，HTTP 缓存控制为 `no-store`，默认 `file.get-ttl=2m`。客户端应使用该地址下载，不把它当作永久公开链接。当前检查对象大小，不重新计算完整摘要；存储中的同大小内容变化不是这个接口能完整识别的情况。

### 在本进程中打开内容

文件服务内部可调用 `FileContentService.openForProcessing(actorUserId, fileId)`，按调用方已认证的用户 ID 查本人可见记录，要求正常且 `COMPLETED`，然后打开对象输入流，返回元数据快照和读取句柄。状态不允许时抛出 `409` 业务异常，不可见记录为 `404`；对象打开失败继续向调用方抛出异常，不提供自动重读。

调用方必须用 `try-with-resources` 关闭句柄；重复关闭按幂等处理，重新读取需要再次调用服务，不能复用已消费的流。这个方法不独立认证传入的用户 ID，也没有 HTTP 入口。内容、用户等独立服务不能直接注入它，更不能通过共享文件实体或伪造用户 ID 代替服务间授权。

目前尚无跨服务内容读取接口、公开文件入口或业务引用保护，不能将私人文件能力直接当作完整头像或视频资产链路。已有问题记录把首个真实调用方接入列为待讨论方向，尚未确认接口方案，不编造调用方式。

源码入口：[内部内容读取](../../service/file-service/src/main/java/com/calles/platform/file/application/content/FileContentService.java)、[读取资源与关闭](../../service/file-service/src/main/java/com/calles/platform/file/application/content/FileContentResource.java)。

### 删除文件与重复删除

调用 `DELETE /api/files/{id}`，只允许本人删除。处理不是“先删对象再看数据库”，而是先写 `delete_requested_at`，把文件标为删除中，从查询、下载和确认入口隐藏；随后删除正式对象及存在的暂存对象，最后写 `deleted_at`，记录删除已完成。

两个对象均明确删除成功或不存在，且数据库删除完成状态明确后，才返回 `204`。重复删除本人已完成删除的记录也返回 `204`；他人或未知文件返回 `404`。

远端结果未知或数据库完成状态未确认时，可能返回 `503`。此时文件仍被隐藏，不能解释为完全没有发生删除；客户端可重复调用 DELETE，或由启用后的恢复任务继续。删除不提供撤销或恢复原文件接口，也不会即时撤销已经发出的所有短期签名凭据。

## Part 4：未完成上传清理与失败恢复

### 从数据库记录继续未完成的工作

清理通过内部定时任务执行，没有对外清理 API。默认 `file.cleanup.enabled=false`；启用后按批次及时间预算处理，不扫描整个 bucket：

- 将到期的 `PENDING / VERIFYING` 转为 `EXPIRED`，尝试删除相关对象，并重试已有过期记录的残留。
- 对未到期的 V2 `VERIFYING` 继续 HEAD、条件复制和完成更新；到期或内容不符时转入失败清理。
- 单独扫描已完成文件的暂存残留，删除后记录清理结果，不影响正式文件的完成状态。
- 对已请求但未完成的删除继续处理对象和数据库删除标记。

单次失败保留后续处理依据，但默认开关关闭时不会自动执行这些恢复。过期记录扫描的游标只在一轮内推进，下一轮又从头开始，且对象删除后仍保留 `EXPIRED` 记录；记录量超过单轮预算时，后面的记录可能长期轮不到。因此当前“有重试”不等于“所有残留最终一定清完”。对象存储的暂存生命周期规则属于真实环境前置配置，应用没有自动创建它，不能把“要求配置”写成“已部署”。当前预算是分批与检查点约束，不等于对每个外部调用都能立即强制取消。

源码入口：[恢复与清理用例](../../service/file-service/src/main/java/com/calles/platform/file/application/cleanup/FileCleanupService.java)、[任务入口](../../service/file-service/src/main/java/com/calles/platform/file/interfaces/scheduling/FileCleanupScheduler.java)、[默认配置](../../service/file-service/src/main/resources/application.yml)。历史问题线索保留在[已有问题记录](../file-issues.md)，不另建重复问题文档。

## 验证方式与当前结果

应验证三类上传的真实完成条件、错误大小与 checksum、确认 `202/200/503` 区别、跨用户拒绝、确认与删除竞争、复制后数据库失败、删除未知结果重试，以及恢复任务关闭和开启时的差异。V2 还需核对私有 bucket、签名端点可达性、浏览器 CORS、条件复制权限和暂存生命周期；这里只记录功能依赖，不表示已经配置。

现有[文件测试目录](../../service/file-service/src/test/java/com/calles/platform/file)包含普通上传、V2 初始化与确认、删除顺序和恢复、配置校验、SQL 条件检查。已阅读的 V2 确认测试通过模拟存储断言 HEAD、条件复制及不调用正文读取；删除测试通过替身验证先建立删除标记再删除对象。它们不是实际存储 checksum 或 MySQL 并发的通过证据。

本次（2026-09-09）实际完成代码、配置、Schema 和相关测试断言的静态核对，以及文档链接与格式检查。未执行 Maven 编译或测试，未执行数据库初始化，未启动 MySQL、MinIO/OSS 或浏览器联调，未执行多实例并发验收。原文提到的历史测试编译不作为本次结果重复宣称。
