-- 新登录会话创建脚本（含设备感知、LRU 会话淘汰与双键 TTL 管理）。
-- KEYS[1] = auth:session:<sid>（必须不存在）
-- KEYS[2] = auth:refresh:<sha256>（必须不存在）
-- KEYS[3] = auth:user:devices:<userId>（设备 → sid 映射 Hash）
-- KEYS[4] = auth:user:sessions:<userId>（全局会话时序 ZSet，score = 活跃时间戳毫秒）
-- ARGV[1] = sid
-- ARGV[2] = subjectId
-- ARGV[3] = role（USER | ADMIN）
-- ARGV[4] = refreshHash
-- ARGV[5] = ISO expiresAt
-- ARGV[6] = 绝对 epoch 毫秒 deadline（决定 session / refresh / devices / sessions 过期时刻）
-- ARGV[7] = deviceId（客户端设备唯一标识）
-- ARGV[8] = maxSessions（单用户最大并发会话数，由配置传入，不硬编码）
-- ARGV[9] = userId（用于 HGET/HDEL devices 映射归属校验，与 subjectId 保持独立语义）
-- 写入顺序：refresh 索引 → session Hash → devices Hash → ZSet，保证 abort 路径无残留。

-- 统一读取 Redis 键类型，避免在异常类型上执行命令造成脚本错误。
local function keyType(key)
    return redis.call('TYPE', key)['ok']
end
-- deadline 必须是正整数毫秒时间戳，避免 Redis 过期参数被隐式转换。
local function positiveInteger(value)
    return value and string.match(value, '^%d+$') and tonumber(value) > 0
end

-- 先固定参数形状，调用方传参错位时不写入任何会话状态。
if #KEYS ~= 4 or #ARGV ~= 9 then
    return 'DEPENDENCY_ERROR'
end
-- 核心业务字段不能为空；maxSessions 和 deadline 必须是正整数；deviceId 不能为空。
if not ARGV[1] or ARGV[1] == '' or not ARGV[2] or ARGV[2] == ''
        or (ARGV[3] ~= 'USER' and ARGV[3] ~= 'ADMIN')
        or not ARGV[4] or ARGV[4] == '' or not ARGV[5] or ARGV[5] == ''
        or not positiveInteger(ARGV[6])
        or not ARGV[7] or ARGV[7] == ''
        or not positiveInteger(ARGV[8])
        or not ARGV[9] or ARGV[9] == '' then
    return 'DEPENDENCY_ERROR'
end
-- session 与 refresh 索引必须同时不存在，防止 sid 或 hash 冲突时只写入半套状态。
if keyType(KEYS[1]) ~= 'none' or keyType(KEYS[2]) ~= 'none' then
    return 'DEPENDENCY_ERROR'
end

-- 以 Redis 服务器时间判断 deadline，避免应用节点时钟偏差导致过期凭据被重新创建。
local now = redis.call('TIME')
local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local deadline = tonumber(ARGV[6])
local maxSessions = tonumber(ARGV[8])
if deadline <= nowMillis then
    return 'INVALID'
end

-- ── 步骤 1：同设备旧会话清理 ──────────────────────────────────────────────────
-- 同一 deviceId 再次登录时，旧会话应立即失效，确保同一设备永远只占 1 个配额。
local oldSid = redis.call('HGET', KEYS[3], ARGV[7])
if oldSid and oldSid ~= '' then
    local oldSessionKey = 'auth:session:' .. oldSid
    -- 读取旧会话的 refreshHash，用于条件删除 refresh 索引。
    local oldRefreshHash = nil
    if keyType(oldSessionKey) == 'hash' then
        oldRefreshHash = redis.call('HGET', oldSessionKey, 'refreshHash')
    end
    -- 仅当 refresh 索引仍指向旧 sid 时才删除，避免轮换中途误删已归属新 sid 的索引。
    if oldRefreshHash and oldRefreshHash ~= '' then
        local oldRefreshKey = 'auth:refresh:' .. oldRefreshHash
        if keyType(oldRefreshKey) == 'string' and redis.call('GET', oldRefreshKey) == oldSid then
            redis.call('DEL', oldRefreshKey)
        end
    end
    -- 删除旧 session（无论 ready 还是 in-flight 状态，新登录均强制覆盖）。
    redis.call('DEL', oldSessionKey)
    -- 从 ZSet 中移除旧 sid，避免其继续占用会话配额。
    redis.call('ZREM', KEYS[4], oldSid)
    -- devices 映射将在步骤 3 中被新 sid 覆盖，无需单独删除旧 field。
end

-- ── 步骤 2：跨设备总配额检查与 LRU 淘汰 ──────────────────────────────────────
-- 只在这是一台新设备（或旧会话已不存在）时才检查总数，避免同设备更新被重复计数。
local sessionCount = redis.call('ZCARD', KEYS[4])
if sessionCount >= maxSessions then
    -- ZPOPMIN 取出 score 最小（最久未活跃）的会话，实现 LRU 淘汰语义。
    local oldest = redis.call('ZPOPMIN', KEYS[4])
    if oldest and #oldest >= 1 then
        local oldestSid = oldest[1]
        local oldestSessionKey = 'auth:session:' .. oldestSid
        -- 读取被淘汰会话的 refreshHash 和 deviceId，用于级联清理。
        local oldestRefreshHash = nil
        local oldestDeviceId = nil
        if keyType(oldestSessionKey) == 'hash' then
            oldestRefreshHash = redis.call('HGET', oldestSessionKey, 'refreshHash')
            oldestDeviceId = redis.call('HGET', oldestSessionKey, 'deviceId')
        end
        -- 条件删除被淘汰会话的 refresh 索引，防止旧设备在下次刷新时获得新 Token。
        if oldestRefreshHash and oldestRefreshHash ~= '' then
            local oldestRefreshKey = 'auth:refresh:' .. oldestRefreshHash
            if keyType(oldestRefreshKey) == 'string'
                    and redis.call('GET', oldestRefreshKey) == oldestSid then
                redis.call('DEL', oldestRefreshKey)
            end
        end
        -- 删除被淘汰的 session Hash。
        redis.call('DEL', oldestSessionKey)
        -- 从 devices 映射中移除被淘汰设备，防止其下次登录触发无效的旧 sid 清理。
        if oldestDeviceId and oldestDeviceId ~= '' then
            -- 仅当映射仍指向被淘汰的 sid 时才删除，避免误删已被新登录覆盖的 field。
            if redis.call('HGET', KEYS[3], oldestDeviceId) == oldestSid then
                redis.call('HDEL', KEYS[3], oldestDeviceId)
            end
        end
    end
end

-- ── 步骤 3：建立新会话（session/refresh 先写，devices/sessions 后写）───────────
-- 先原子占用 refresh 索引；占用失败时不创建任何状态，避免留下无法定位的会话。
local indexResult = redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PXAT', deadline)
local success = (type(indexResult) == 'table' and indexResult['ok'] == 'OK') or indexResult == 'OK'
if not success then
    return 'DEPENDENCY_ERROR'
end
-- 索引已成功落位后写 session Hash，追加 deviceId 字段供注销时反查设备映射。
redis.call('HSET', KEYS[1], 'subjectId', ARGV[2], 'role', ARGV[3],
    'refreshHash', ARGV[4], 'expiresAt', ARGV[5], 'deviceId', ARGV[7])
redis.call('PEXPIREAT', KEYS[1], deadline)

-- session 和 refresh 均已落盘后，再写 devices 映射和 ZSet；
-- 此分界点之前崩溃时 abort 只需清理 session/refresh，不会有 devices/sessions 残留。
redis.call('HSET', KEYS[3], ARGV[7], ARGV[1])
redis.call('ZADD', KEYS[4], nowMillis, ARGV[1])

-- ── 步骤 4：为两个新键设置 TTL ────────────────────────────────────────────────
-- 使用 GT 选项，只在新 deadline 更大时才更新 TTL，保护已有更晚会话的生命周期。
-- 要求 Redis 6.2+；若环境不满足，去掉 'GT' 改为无条件覆盖。
redis.call('PEXPIREAT', KEYS[3], deadline, 'GT')
redis.call('PEXPIREAT', KEYS[4], deadline, 'GT')
return 'OK'
