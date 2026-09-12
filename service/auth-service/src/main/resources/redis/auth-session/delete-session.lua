-- 注销/会话删除脚本（含设备映射与会话时序清理）。
-- KEYS[1] = auth:session:<sid>
-- KEYS[2] = auth:user:devices:<userId>（设备映射 Hash）
-- KEYS[3] = auth:user:sessions:<userId>（会话时序 ZSet）
-- ARGV[1] = sid（用于 devices 映射归属校验，防止误删新登录设备的映射）
-- 仅在 refresh 索引仍指向本 sid 时删除该索引，再同步清理 devices 和 ZSet，最后删除 session。

-- 统一读取键类型，避免在被错误写入的键上执行命令。
local function keyType(key)
    return redis.call('TYPE', key)['ok']
end
-- 删除操作不接受多余或缺失的键和参数，防止调用方误传时扩大删除范围。
if #KEYS ~= 3 or #ARGV ~= 1 or not ARGV[1] or ARGV[1] == '' then
    return 'DEPENDENCY_ERROR'
end
-- session 不存在视为注销已完成，使重复注销请求保持幂等成功。
if keyType(KEYS[1]) == 'none' then
    return 'OK'
end
if keyType(KEYS[1]) ~= 'hash' then
    return 'DEPENDENCY_ERROR'
end

-- 从 session 取得当前 refreshHash；无 Hash 的异常旧状态仍会在最后被删除。
local refreshHash = redis.call('HGET', KEYS[1], 'refreshHash')
if refreshHash and refreshHash ~= '' then
    local indexKey = 'auth:refresh:' .. refreshHash
    -- 仅当 refresh 索引仍指向当前 sid 才删除，避免清理轮换后已归属其他会话的索引。
    if keyType(indexKey) == 'string' and redis.call('GET', indexKey) == string.sub(KEYS[1], 14) then
        redis.call('DEL', indexKey)
    end
end

-- 从 devices 映射中移除当前设备，防止下次登录触发无效的旧 sid 清理。
-- 仅当映射中的 field 仍指向本 sid 时才删除，避免误删同设备新登录后写入的映射。
local deviceId = redis.call('HGET', KEYS[1], 'deviceId')
if deviceId and deviceId ~= '' then
    if redis.call('HGET', KEYS[2], deviceId) == ARGV[1] then
        redis.call('HDEL', KEYS[2], deviceId)
    end
end

-- 从会话时序 ZSet 中移除本 sid，释放会话配额。
redis.call('ZREM', KEYS[3], ARGV[1])

-- 无论 ready 或 in-flight 状态均删除 session，阻止迟到的 finish 请求重新提交该会话。
redis.call('DEL', KEYS[1])
return 'OK'
