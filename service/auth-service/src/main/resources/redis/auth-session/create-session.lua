-- 新登录会话创建脚本。
-- KEYS[1] = auth:session:<sid>（必须不存在）
-- KEYS[2] = auth:refresh:<sha256>（必须不存在）
-- ARGV = sid、subjectId、role、refreshHash、ISO expiresAt、绝对 epoch 毫秒 deadline
-- 会话 Hash 与 Refresh 索引使用同一 PXAT deadline，避免处理耗时延长凭据寿命。

-- 统一读取 Redis 键类型，避免在异常类型上执行 GET/HSET 造成脚本错误。
local function keyType(key)
    return redis.call('TYPE', key)['ok']
end
-- deadline 必须是正整数毫秒时间戳，避免 Redis 过期参数被隐式转换。
local function positiveInteger(value)
    return value and string.match(value, '^%d+$') and tonumber(value) > 0
end

-- 先固定参数形状，调用方传参错位时不写入任何会话状态。
if #KEYS ~= 2 or #ARGV ~= 6 then
    return 'DEPENDENCY_ERROR'
end
-- 会话、主体、角色和 Refresh Hash 都是后续校验的归属依据，不能接受空值或未知角色。
if not ARGV[1] or ARGV[1] == '' or not ARGV[2] or ARGV[2] == ''
        or (ARGV[3] ~= 'USER' and ARGV[3] ~= 'ADMIN')
        or not ARGV[4] or ARGV[4] == '' or not ARGV[5] or ARGV[5] == ''
        or not positiveInteger(ARGV[6]) then
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
if deadline <= nowMillis then
    return 'INVALID'
end

-- 先原子占用 refresh 索引；占用失败时不创建 Hash，避免留下无法定位的会话。
local indexResult = redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PXAT', deadline)
if indexResult ~= 'OK' then
    return 'DEPENDENCY_ERROR'
end
-- 索引已成功落位后再写会话，并使用同一绝对过期时间保持两把键的生命周期一致。
redis.call('HSET', KEYS[1], 'subjectId', ARGV[2], 'role', ARGV[3],
    'refreshHash', ARGV[4], 'expiresAt', ARGV[5])
redis.call('PEXPIREAT', KEYS[1], deadline)
return 'OK'
