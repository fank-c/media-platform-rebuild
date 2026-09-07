-- Refresh 轮换 abort 脚本：仅条件删除当前请求仍拥有的 in-flight 会话。
-- KEYS[1] = auth:session:<sid>；KEYS[2]/KEYS[3] = 旧/候选 refresh 索引
-- ARGV = sid、subjectId、rotationId。
-- rotationId 或 state 不匹配时无副作用，不能误删 logout、完成轮换或新登录后的状态。

-- 统一检查键类型；abort 是补偿路径，异常键一律无副作用地结束。
local function keyType(key)
    return redis.call('TYPE', key)['ok']
end
-- 参数不完整时返回 NOOP，避免补偿请求因调用错误误删未知会话。
if #KEYS ~= 3 or #ARGV ~= 3 or not ARGV[1] or ARGV[1] == ''
        or not ARGV[2] or ARGV[2] == '' or not ARGV[3] or ARGV[3] == '' then
    return 'NOOP'
end
-- 会话已被 logout、完成轮换或其他补偿清理时，abort 按幂等无操作处理。
if keyType(KEYS[1]) == 'none' then
    return 'NOOP'
end
if keyType(KEYS[1]) ~= 'hash' then
    return 'NOOP'
end
-- subjectId、rotationId 与 inflight 三重归属校验全部通过，才允许清理本次请求的状态。
if redis.call('HGET', KEYS[1], 'subjectId') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'rotationId') ~= ARGV[3]
        or redis.call('HGET', KEYS[1], 'rotationState') ~= 'inflight' then
    return 'NOOP'
end

-- 只删除仍为字符串且仍指向当前 sid 的旧/候选索引，不能波及其他请求创建的新索引。
for _, indexKey in ipairs({KEYS[2], KEYS[3]}) do
    if keyType(indexKey) == 'string' and redis.call('GET', indexKey) == ARGV[1] then
        redis.call('DEL', indexKey)
    end
end
-- 索引清理完成后才删除 in-flight session；删除 session 是补偿的最后一步。
redis.call('DEL', KEYS[1])
return 'ABORTED'
