# 互动模块已知问题清单 · interaction-service

> 本文件记录互动模块的已知问题，随仓库版本管理。

- 核对基线：`ac4d717 refactor(interaction): 收敛观看心跳会话与播放资格决策`
- 核对方式：阅读源码与 Mapper SQL；**未编写复现用例**。标注“推断”的条目只做了代码推导，没有实际跑过。
- 优先级：**P0** = 数据错误或越权；**P1** = 统计/推荐信号失真，或错误码不对；**P2** = 边界问题、技术债。
- 状态：`待处理` / `待决策`（得先定规则才能改）/ `文档已同步`（只剩代码残留）。

## 总览

| 编号 | 优先级 | 分类 | 标题 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| INT-01 | P0 | 安全 | 收藏夹缺少属主校验，能读写他人收藏夹 | 待处理 |
| INT-02 | P0 | 计数 | 缓存过期后单字段 Hash 被当成完整快照，刷盘覆盖丢数 | 待处理 |
| INT-03 | P1 | 防刷 | 心跳的增量、视频时长和进度都信客户端 | 待决策 |
| INT-04 | P1 | 一致性 | 点赞/收藏/分享在事务内写 Redis 计数，回滚后计数偏大 | 待处理 |
| INT-05 | P1 | 语义 | 完播防重不一致：删历史能重复完播，正常重看永远不再完播 | 待决策 |
| INT-06 | P1 | 计数 | 刷盘失败丢脏标记；降级到本机内存后多实例计数分裂 | 待处理 |
| INT-07 | P1 | 并发 | 首次点赞/收藏/分享先查后写，并发时 500 或重复计数（推断） | 待处理 |
| INT-08 | P1 | 契约 | 分享幂等键冲突返回 500，不是 409 | 待处理 |
| INT-09 | P1 | 网关 | “匿名可访问”的查询接口实际被网关拦截，返回 401 | 待决策 |
| INT-10 | P2 | 语义 | 会话一直不断时，同一会话内也能触发 REPEAT 播放 | 待处理 |
| INT-11 | P2 | 语义 | 锁等待超时降级时，这次心跳的时长直接丢掉 | 待处理 |
| INT-12 | P2 | 可测性 | 实体和应用层各自取 `LocalDateTime.now()`，两个时间源 | 待处理 |
| INT-13 | P2 | 技术债 | 起播残留代码和 `PLAY_START` 常量没人用 | 文档已同步 |
| INT-14 | P2 | 功能缺口 | 收藏夹没有重名校验、改名和删除接口；GET 查询会写库 | 待处理 |
| INT-15 | P2 | 下游 | 推荐侧没有消费者，Outbox 只进不出，也没有清理策略 | 待处理 |

---

## P0

### INT-01 收藏夹缺少属主校验，能读写他人收藏夹

- **位置**：`StarApplicationService.resolveFolder()`、`StarApplicationService.getStarItems()`、`StarItemRepositoryImpl.findByFolderId()`。
- **现状**：
  - `resolveFolder` 收到 `folderId` 后只查 `findById(...).filter(isActive)`，不比对 `userId`。
  - `POST /videos/{vid}/star` 传别人的 `folderId`，明细会写进别人的收藏夹（明细的 `user_id` 是攻击者）。
  - `GET /star/items?folderId=` 按 `folder_id` 直接分页，能读到任何人的收藏夹内容。
  - 取消收藏会带 `user_id` 过滤，这条路径没有越权。
- **影响**：水平越权（IDOR，改个 ID 就能访问别人的数据），既能读也能写。
- **建议**：`resolveFolder` 和 `getStarItems` 统一校验 `folder.userId == 当前用户`，不匹配时按“不存在”返回 404，避免暴露收藏夹是否存在。补一条越权单测。

### INT-02 缓存过期后单字段 Hash 被当成完整快照，刷盘覆盖丢数

- **位置**：`VideoCounterRedisCache.adjustField()` / `getCounter()` / `getSnapshotForFlush()`，`VideoCounterFlushScheduler`，`VideoCounterMapper.upsertSnapshot()`。
- **现状**：
  1. 计数 Key `int:counter:{vid}` 过期后（`cache-ttl` 默认 10m），下一次 `HINCRBY` 会新建一个**只有单个字段**的 Hash，值从 0 开始算。
  2. 读取时只要 Hash 非空就当命中，缺的字段按 0 解析。
  3. 刷盘用 `upsertSnapshot` 把绝对值**覆盖**进 DB，`view/like/star/share/comment_count` 全部被这个残缺快照覆盖。
  4. `parseFromHashEntries` 把 `commentCount` 固定成 0，每次刷盘都会清零 `comment_count`（评论还没实现，暂时没后果）。
- **影响**：冷视频过期后，只要有一次互动，DB 里的历史累计计数就会被清零或变小。**这是持久数据损坏。**
- **建议**（二选一，需要评估）：
  - 写之前先确认 Key 存在，不存在就从 DB 冷加载完整 Hash（Lua 原子执行“存在检查 + 加载 + 增量”）；
  - 或者改成刷**增量**：Redis 只记 delta，刷盘用 `col = col + delta`，DB 保持累计值，缓存只当读缓存。

---

## P1

### INT-03 心跳的增量、视频时长和进度都信客户端

- **位置**：`WatchHeartbeatApplicationService.processHeartbeat()`、`WatchHistory.isSessionQualified()` / `shouldClaimCompletion()`。
- **现状**：
  - `deltaDuration` 只截断到单次最多 15 秒，没有按服务端时间差校验，高频上报能迅速累积会话时长。
  - `videoDuration` 用客户端传的值，传小了 30% 门槛和完播门槛就跟着降低。
  - 完播只看 `lastPosition >= 90%`，拖动进度条就能触发 `PLAY_COMPLETE`。
- **影响**：播放量有单会话 CAS 和 6h 冷却兜底，影响有限。**30% 资格和完播信号基本不可信**，会污染推荐画像。
- **建议**：
  - 用服务端时间约束增量：`delta ≤ now − lastWatchAt + 容差`。
  - 时长改用可信来源。
  - 完播要求“位置达标，且会话观看时长也达标”。
- **待决策**：视频时长从哪来？A 订阅 content 的发布事件，本地存时长快照（推荐，异步，不引入同步依赖）；B 心跳时同步调 content（需要超时、降级设计和 ADR）。

### INT-04 点赞/收藏/分享在事务内写 Redis 计数，回滚后计数偏大

- **位置**：`LikeApplicationService`、`StarApplicationService`、`InteractionQueryApplicationService.recordShare()`，调用 `VideoCounterRepository.adjust*/increment*`。
- **现状**：这三处在 `@Transactional` 里直接执行 `HINCRBY`。Outbox 写入或后续步骤失败、事务回滚时，Redis 计数不会跟着回滚。播放量已经改成 `afterCommit`（事务提交后再执行），四种计数的写法不统一。
- **建议**：抽一个公共的“提交后调整计数”方法，四类计数统一走 `afterCommit`。

### INT-05 完播防重不一致：删历史能重复完播，正常重看永远不再完播

- **位置**：`WatchHistory.revive()`（重置 `completed = false`），`WatchHistoryMapper.markCompletedIfUncompleted()`。
- **现状**：
  - 逻辑删除后再看会走复活，`completed` 被清零，能再次发出 `PLAY_COMPLETE`。
  - 没删过的记录，完播只能置位一次，之后每次重看都不会再有完播信号。
- **影响**：和 `PLAY` 的防刷思路正好相反，完播信号的含义不清。
- **待决策**：完播按“每个用户每个视频一次”算（`revive` 不再重置），还是像 `PLAY` 一样按会话算并受冷却约束（需要新增会话级完播标记）。

### INT-06 刷盘失败丢脏标记；降级到本机内存后多实例计数分裂

- **位置**：`VideoCounterRedisCache.popDirtyVids()` / 本地降级分支，`VideoCounterFlushScheduler`。
- **现状**：
  - 脏标记是先 `SPOP` 再刷盘，刷盘失败后标记已经没了，只能等这个视频下次变动。如果这期间 Key 过期，增量就永久丢失。
  - Redis 异常时降级写本机 `ConcurrentHashMap`。多实例之间计数不共享，进程重启数据就没了；Redis 恢复后，本地的脏数据也不会合并回去。
- **建议**：刷盘失败时把 vid 重新放回脏集合（或者改成“先读后删”）。降级策略要重新评估：写路径可以直接失败，或只在单实例模式下允许降级，并在文档里写明。

### INT-07 首次点赞/收藏/分享先查后写，并发时 500 或重复计数（推断）

- **位置**：`LikeApplicationService.likeVideo()`、`StarApplicationService.starVideo()`、`InteractionQueryApplicationService.recordShare()`。
- **现状（推断，未复现）**：
  - 同一用户并发首次点赞：两边都查不到记录，一边插入成功，另一边撞上 `uk_like_user_vid`，抛 `DuplicateKeyException`，最后被兜底处理成 500。
  - 并发收藏同一视频到两个不同收藏夹：两边的 `isStarredByUser` 都返回 false，收藏数 +2，还会发两条 `STAR:ACTIVE`。
  - 同一幂等键并发分享：撞上 `uk_share_idempotency`，返回 500。
- **建议**：捕获唯一键冲突后重读，转成幂等成功；收藏按 `userId:vid` 加锁，或者用条件更新代替先查后写。

### INT-08 分享幂等键冲突返回 500，不是 409

- **位置**：`InteractionQueryApplicationService.recordShare()` 抛 `IllegalStateException`，`InteractionExceptionHandler` 没有对应处理。
- **现状**：同一个键被别的用户或别的视频用过时，落到兜底处理，返回 500，看起来像服务故障。
- **建议**：改抛 `InteractionException(HttpStatus.CONFLICT, ...)`。另外幂等键目前全局唯一，可以考虑按 `userId + key` 限定作用域。

### INT-09 “匿名可访问”的查询接口实际被网关拦截，返回 401

- **位置**：`gateway-service` 的 `AuthProperties.whitelist`（不含 `/api/interactions/**`）。
- **现状**：`/stat`、`/stats`、`/my-state`、`/watch-progress` 在服务内部允许匿名，但经过网关时游客一律 401。
- **待决策**：公开统计是否对游客开放？开放就要把 `GET /api/interactions/videos/*/stat` 等加入网关白名单，并同步 `docs/modules/gateway.md`；不开放就在服务侧统一 `requireUser`。这件事改变的是对外鉴权语义，需要你确认。

---

## P2

### INT-10 会话一直不断时，同一会话内也能触发 REPEAT 播放

- **位置**：`WatchHistory.markEligibleForNextPlayIfSessionQualified()`、`decidePlayClaim()`。
- **现状**：30% 资格由**当前**会话时长实时置位。举例：距上次有效播放 5h 时开启新会话，这时冷却期还没过，REPEAT 失败，`sessionPlayEmitted` 仍为 false；本会话看满 30% 后 `eligibleForNextPlay` 置为 true；会话持续到 6h 冷却期过后，**同一会话**里的某次心跳就会抢到 REPEAT。这违背了文档写的“上一会话达标 + 新会话”条件。
- **建议**：把“当前会话已达标”和“下一会话有资格”拆成两个字段；只在会话切换时，把前者转成后者。

### INT-11 锁等待超时降级时，这次心跳的时长直接丢掉

- **位置**：`WatchHeartbeatApplicationService.processHeartbeat()` 的 `LockAcquireTimeoutException` 分支。
- **现状**：只读返回已有断点，本次的 `deltaDuration` 不入账，也不提示客户端重试。
- **建议**：在文档里说明这个行为；或者在响应里加标记，让客户端下次心跳时把时长合并补报。

### INT-12 实体和应用层各自取 `LocalDateTime.now()`，两个时间源

- **位置**：`WatchHistory.create()` / `recordHeartbeat()` / `revive()` 自己取 `now()`，应用层又另取一个 `now` 传给会话判断和 CAS。
- **影响**：同一次心跳里 `lastWatchAt` 和 `last_valid_play_at` 可能差几毫秒；测试也没法控制时间。
- **建议**：注入 `Clock`（配置里已经有 `@ConditionalOnMissingBean(Clock.class)`），由应用层统一传入 `now`。

### INT-13 起播残留代码和 `PLAY_START` 常量没人用

- **位置**：`WatchHistory.createForPlay()` / `recordPlayStart()`，`WatchHeartbeatApplicationService.startPlay()`，`VideoActionPayload.ACTION_PLAY_START` / `playStart()`。
- **现状**：起播接口已经并进心跳，这些成员已经没有调用方了。文档已写明 `PLAY_START` 为保留值、不会发送。
- **建议**：删掉这些死代码，或加 `@Deprecated`，免得有人误以为是契约的一部分。

### INT-14 收藏夹没有重名校验、改名和删除接口；GET 查询会写库

- **现状**：
  - `createCustomFolder` 不检查同名；`StarFolder.rename()` 没有暴露成接口；也没有删除收藏夹的接口。旧 TODO 标成“已完成”的“重名检查、级联删除”其实没实现。
  - `GET /star/folders` 在没有收藏夹时会插入默认收藏夹，GET 请求有写副作用。
  - `interaction_star_folder` 里 `status` 和 `deleted` 两个字段都表示删除，语义重复。
- **建议**：列为收藏夹管理的后续功能点，统一决定删除语义。

### INT-15 推荐侧没有消费者，Outbox 只进不出，也没有清理策略

- **现状**：
  - `dispatch-enabled=false`，所有事件都一直停在 `PENDING`。
  - `recommend-service` 还没有 `interaction.video-action.v1` 的队列和消费者。
  - `interaction_outbox` 没有归档或清理任务，表会一直变大。
- **建议**：先做推荐侧的幂等消费者，再打开投递；同时补一个 `PUBLISHED` 记录的保留期清理任务。

---

## 其他（仓库级，非互动模块）

- 工作区有约 674 个文件处于修改状态，实际内容只差 `.gitignore` 一行，其余都是 CRLF/LF 换行差异（索引是 LF，工作区是 CRLF）。建议加 `.gitattributes` 或配置 `core.autocrlf`，避免真实改动被换行噪音淹没。
