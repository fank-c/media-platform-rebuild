-- Refresh 轮换 begin 阶段脚本：原子消费旧索引，并将原 sid 标记为 in-flight。
-- KEYS[1] = auth:refresh:<旧 refresh SHA-256>
-- ARGV[1] = 旧 refresh SHA-256；ARGV[2] = 当前请求唯一 rotationId
-- 成功只返回内部快照；不会返回原始 Refresh Token，也不会删除 session Hash。

-- 统一检查键类型，错误类型只返回受控结果，避免 Lua 命令类型异常外泄。
local function keyType(key)
    return redis.call('TYPE', key)['ok']
end

-- 参数必须完整；调用错误属于依赖故障，不应被伪装成过期 Refresh Token。
if #KEYS ~= 1 or #ARGV ~= 2 or not ARGV[1] or ARGV[1] == ''
        or not ARGV[2] or ARGV[2] == '' then
    return 'DEPENDENCY_ERROR'
end
-- 旧索引不存在或已经无 TTL 时不可继续，避免消费长期残留或已过期的 refresh 映射。
if keyType(KEYS[1]) == 'none' or redis.call('PTTL', KEYS[1]) <= 0 then
    return 'INVALID'
end
if keyType(KEYS[1]) ~= 'string' then
    return 'DEPENDENCY_ERROR'
end

-- 索引只保存 sid；后续必须回查 session，不能仅凭索引签发新凭据。
local sid = redis.call('GET', KEYS[1])
if not sid or sid == '' then
    return 'INVALID'
end
local sessionKey = 'auth:session:' .. sid
-- 索引和 session 都必须存活，索引指向不存在/无 TTL 的会话视为无效。
if keyType(sessionKey) == 'none' or redis.call('PTTL', sessionKey) <= 0 then
    return 'INVALID'
end
if keyType(sessionKey) ~= 'hash' then
    return 'DEPENDENCY_ERROR'
end

-- 读取生成新令牌所需的受控快照，并校验索引 Hash 仍与会话字段绑定。
local subjectId = redis.call('HGET', sessionKey, 'subjectId')
local role = redis.call('HGET', sessionKey, 'role')
local refreshHash = redis.call('HGET', sessionKey, 'refreshHash')
local expiresAt = redis.call('HGET', sessionKey, 'expiresAt')
local rotationId = redis.call('HGET', sessionKey, 'rotationId')
local rotationState = redis.call('HGET', sessionKey, 'rotationState')
if not subjectId or subjectId == '' or not refreshHash or refreshHash == ''
        or not expiresAt or expiresAt == ''
        or (role ~= 'USER' and role ~= 'ADMIN')
        or refreshHash ~= ARGV[1] then
    return 'INVALID'
end
-- rotation 字段必须成对出现，且唯一允许的遗留状态是完整的 inflight；异常状态直接失败关闭。
if (rotationId and not rotationState) or (not rotationId and rotationState)
        or (rotationId and (rotationId == '' or rotationState ~= 'inflight')) then
    return 'INVALID'
end
if rotationId or rotationState then
    return 'INVALID'
end

-- 先生成包含本次 rotationId 的快照，供应用在 Redis 事务外签发候选令牌。
local snapshot = cjson.encode({sessionId=sid, subjectId=subjectId, role=role,
    refreshHash=refreshHash, expiresAt=expiresAt, rotationId=ARGV[2]})
-- 标记 in-flight 后删除旧索引：旧 Refresh Token 立即不可重放，但 session 本身仍保留给 finish/abort 收敛。
redis.call('HSET', sessionKey, 'rotationId', ARGV[2], 'rotationState', 'inflight')
redis.call('DEL', KEYS[1])
return 'BEGIN_OK:' .. snapshot
