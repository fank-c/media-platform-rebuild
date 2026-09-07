-- 注销/会话删除脚本。
-- KEYS[1] = auth:session:<sid>
-- 仅在 refresh 索引仍指向本 sid 时删除该索引，再删除 session；ready 与 in-flight 状态均可删除。

-- 统一读取键类型，避免在被错误写入的 session 键上执行 Hash 命令。
local function keyType(key)
    return redis.call('TYPE', key)['ok']
end
-- 删除操作不接受多余或缺失键，防止调用方误传键时扩大删除范围。
if #KEYS ~= 1 then
    return 'DEPENDENCY_ERROR'
end
-- session 不存在视为注销已完成，使重复注销请求保持幂等成功。
if keyType(KEYS[1]) == 'none' then
    return 'OK'
end
if keyType(KEYS[1]) ~= 'hash' then
    return 'DEPENDENCY_ERROR'
end
-- 从 session 取得当前 Refresh Hash；无 Hash 的异常旧状态仍会在最后被删除。
local refreshHash = redis.call('HGET', KEYS[1], 'refreshHash')
if refreshHash and refreshHash ~= '' then
    local indexKey = 'auth:refresh:' .. refreshHash
    -- 仅当 refresh 索引仍指向当前 sid 才删除，避免清理轮换后已归属其他会话的索引。
    if keyType(indexKey) == 'string' and redis.call('GET', indexKey) == string.sub(KEYS[1], 14) then
        redis.call('DEL', indexKey)
    end
end
-- 无论 ready 或 in-flight 状态均删除 session，阻止迟到的 finish 请求重新提交该会话。
redis.call('DEL', KEYS[1])
return 'OK'
