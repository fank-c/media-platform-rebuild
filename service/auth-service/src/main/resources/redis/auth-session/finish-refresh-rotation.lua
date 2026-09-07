-- Refresh 轮换 finish 阶段脚本：仅拥有相同 rotationId 的请求可以提交候选凭据。
-- KEYS[1] = auth:session:<sid>；KEYS[2] = auth:refresh:<新 refresh SHA-256>
-- ARGV = sid、subjectId、rotationId、旧/新 refreshHash、最新 role、ISO expiresAt、绝对 epoch 毫秒 deadline
-- 先以 SET NX PXAT 写新索引，再更新会话并移除 in-flight 标记；任何不匹配均失败关闭。

-- 统一检查键类型，避免在错误类型键上继续执行会话更新。
local function keyType(key)
    return redis.call('TYPE', key)['ok']
end
-- 新会话 deadline 必须为正整数毫秒时间戳，确保 PXAT 行为确定。
local function positiveInteger(value)
    return value and string.match(value, '^%d+$') and tonumber(value) > 0
end

-- finish 需要完整的旧状态归属和候选状态；参数错位时禁止修改 in-flight 会话。
if #KEYS ~= 2 or #ARGV ~= 8 or not ARGV[1] or ARGV[1] == ''
        or not ARGV[2] or ARGV[2] == '' or not ARGV[3] or ARGV[3] == ''
        or not ARGV[4] or ARGV[4] == '' or not ARGV[5] or ARGV[5] == ''
        or (ARGV[6] ~= 'USER' and ARGV[6] ~= 'ADMIN')
        or not ARGV[7] or ARGV[7] == '' or not ARGV[8] or ARGV[8] == '' or not positiveInteger(ARGV[8]) then
    return 'DEPENDENCY_ERROR'
end
-- 新旧 Refresh Hash 必须不同，否则轮换不会废弃旧凭据且会破坏一次性语义。
if ARGV[4] == ARGV[5] then
    return 'DEPENDENCY_ERROR'
end
-- session 必须仍存在且带 TTL；过期后的 finish 不能复活已失效会话。
if keyType(KEYS[1]) == 'none' or redis.call('PTTL', KEYS[1]) <= 0 then
    return 'INVALID'
end
if keyType(KEYS[1]) ~= 'hash' then
    return 'DEPENDENCY_ERROR'
end

-- 同时校验主体、旧 Hash、rotationId 和 inflight 状态，确保只有发起该轮换的请求能提交。
local subjectId = redis.call('HGET', KEYS[1], 'subjectId')
local refreshHash = redis.call('HGET', KEYS[1], 'refreshHash')
local rotationId = redis.call('HGET', KEYS[1], 'rotationId')
local rotationState = redis.call('HGET', KEYS[1], 'rotationState')
if subjectId ~= ARGV[2] or refreshHash ~= ARGV[4]
        or rotationId ~= ARGV[3] or rotationState ~= 'inflight' then
    return 'INVALID'
end

-- 候选 refresh 索引绝不覆盖已有键，避免 hash 冲突时劫持其他会话。
local newIndexType = keyType(KEYS[2])
if newIndexType ~= 'none' then
    return 'DEPENDENCY_ERROR'
end
-- 用 Redis 时间验证绝对 deadline，避免应用节点时钟偏差延长候选凭据寿命。
local now = redis.call('TIME')
local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local deadline = tonumber(ARGV[8])
if deadline <= nowMillis then
    return 'INVALID'
end

-- 先用 NX 创建新索引；只有索引成功后，才允许替换会话 Hash 并结束 in-flight 状态。
local indexResult = redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PXAT', deadline)
if indexResult ~= 'OK' then
    return 'DEPENDENCY_ERROR'
end
-- 新索引已落位后更新 session，并用同一 deadline 续期，最后移除轮换占用标记。
redis.call('HSET', KEYS[1], 'role', ARGV[6], 'refreshHash', ARGV[5], 'expiresAt', ARGV[7])
--
redis.call('PEXPIREAT', KEYS[1], deadline)
redis.call('HDEL', KEYS[1], 'rotationId', 'rotationState')
return 'OK'
