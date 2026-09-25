# ADR 0003: 互动服务引入 Redisson 分布式锁串行化观看心跳

## 状态

**Accepted（已采纳，追认）**。依赖在提交 `9efff70` 引入，本文补齐记录。

## 背景

同一用户对同一视频的心跳可能并发到达：多个标签页、客户端重试、多实例负载均衡都会造成这种情况。每次心跳都是“读观看记录，累计会话时长，判断播放资格，再写回”，这是一个读-改-写过程。只靠数据库 CAS 有两个问题：

- CAS 能保证 PLAY、完播事件只发一次，但**会话时长字段是先读后写的**，并发时会互相覆盖，累计值变少。
- 如果锁在事务提交前就释放，下一个请求可能读到还没提交的旧快照。

引入之前，心跳链路没有任何锁，并发时只靠 CAS 兜底。

## 决策

1. **引入 `org.redisson:redisson-spring-boot-starter`**
   - 版本在根 `pom.xml` 统一管理（`redisson.version`，当前 3.31.0）。
   - 目前只有 `interaction-service` 使用，复用现有的 `spring.data.redis` 连接配置，不新增基础设施。
2. **锁粒度和用法**
   - Key 为 `int:lock:watch:{userId}:{vid}`。
   - 最多等 3 秒。不设租约，由看门狗（Watchdog，锁持有期间后台自动续期的机制）续期。
   - 顺序是**锁在外、事务在内**：先拿锁，再开事务，事务提交后才释放锁。
3. **数据库 CAS 仍是最终防线**。锁只用来减少冲突、保护会话累计的正确性。PLAY 和完播是否发出，由 CAS 条件最终决定。
4. **降级策略**（`RedisLockService`）：
   - Redis 正常、但锁被别人持有且等待超时：抛 `LockAcquireTimeoutException`，心跳按只读降级处理（返回已有进度，本次时长不入账）。**这种情况下不允许退回本地锁**，否则就绕过了分布式互斥。
   - Redis 不可用，或 Redisson 未配置：降级为单机 JVM 的 `ReentrantLock`，只保证本实例内互斥。
5. **封装位置**：`RedisLockService` 放在 `interaction-service` 的 `infrastructure/redis` 下，不放进 `common`。其他服务要用，得先证明确实需要，再另行决定是抽成共享模块还是各自实现。

## 备选方案

| 方案 | 不选的原因 |
| :--- | :--- |
| 只用数据库 CAS 或乐观锁版本号 | 高频心跳下冲突多，需要重试；会话累计字段要大面积改成 SQL 原子累加，改动大 |
| `SELECT ... FOR UPDATE` 行锁 | 首次插入时还没有这一行，锁不住；长事务还会占用连接 |
| 自己写 `SETNX` 加 Lua 解锁 | 没有续期，而且要自己处理可重入和误删别人的锁 |
| 只把一个用户的请求路由到固定实例 | 依赖网关的一致性哈希，扩缩容时会失效，还增加网关复杂度 |

## 后果

- **收益**：同一 `userId:vid` 的心跳被串行处理，会话累计不会丢；锁只覆盖同一用户同一视频，对整体吞吐影响很小。
- **代价与风险**：
  - 多了一个第三方依赖。Redisson 和 Spring Boot、Spring Data Redis 的版本要一起升级。
  - Redis 故障时，多实例之间的互斥降级为单机互斥，这时只剩 CAS 兜底，会话累计可能出现少量偏差。
  - 等锁超时的请求会丢掉本次时长，这是有意的取舍：宁可少记，也不重复记。
- **验证**：`RedisLockServiceTest`，以及基于本地 Redis/MySQL 的 `WatchHeartbeatLocalInfrastructureIntegrationTest`。
