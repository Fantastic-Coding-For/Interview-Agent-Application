-- 单维度限流脚本（Redis Lua）
-- 基于滑动时间窗口的单维度原子限流
-- RateLimitAspect 切面最终调用的核心原子逻辑

-- 限流 Redis Key 前缀（根据限流维度等内容拼接）
local key = KEYS[1]
-- 系统当前毫秒时间戳
local now_ms = tonumber(ARGV[1])
-- 本次要消耗的令牌数
local permits = tonumber(ARGV[2])
-- interval * timeUnit 换算后的毫秒数（滑动窗口大小）
local interval = tonumber(ARGV[3])
-- 窗口内允许的最大令牌数
local max_tokens = tonumber(ARGV[4])
-- 请求唯一标识
local request_id = ARGV[5]

-- 存储 当前剩余的可用令牌数
local value_key = key .. ":value"
-- 存储 历史请求的消耗记录
local permits_key = key .. ":permits"

-- 获取当前可用令牌（若为第一次调用 key 不存在，则使用 max_tokens）
local current_val = tonumber(redis.call("get", value_key)) or max_tokens

-- 回收过期令牌
-- 取出所有 时间戳 < (当前时间 - 窗口大小) 的记录，即已经滑出窗口外的“过期请求”。
-- 使用 ZRANGEBYSCORE 先查询（不删除），解析每个记录中消耗的令牌数并累加，
-- 然后用 ZREMRANGEBYSCORE 一次性删除。
--
-- 为什么用 "先查后删" 而非直接 ZREMRANGEBYSCORE + 返回值？
-- ZREMRANGEBYSCORE 只返回删除的 member 数量，不返回 member 的内容。
-- 而我们需要从 member 中解析出消耗的令牌数（member 格式：requestId:permits）。
-- 所以需要先 ZRANGEBYSCORE 获取 member 内容来累加，再删除。
local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
if #expired_values > 0 then
    local expired_count = 0
    for _, v in ipairs(expired_values) do
        local p = tonumber(string.match(v, ":(%d+)$"))
        if p then
            expired_count = expired_count + p
        end
    end

    redis.call("zremrangebyscore", permits_key, 0, now_ms - interval)

    if expired_count > 0 then
        current_val = math.min(max_tokens, current_val + expired_count)
    end
end

-- 检查可用令牌
-- 如果当前剩余配额不够本次申请的（通常为 1），直接返回 0，AOP 切面收到 0 就会触发降级或抛异常。
if current_val < permits then
    return 0
end

-- 扣减令牌
-- 原子性地将本次消耗写入 ZSet，同时更新 String 的剩余值。
local permit_record = request_id .. ":" .. permits
redis.call("zadd", permits_key, now_ms, permit_record)
redis.call("set", value_key, current_val - permits)

-- 设置过期时间（窗口的2倍，至少1秒）
-- 给相应 Key 设置 TTL 为 2倍窗口大小，防止极端情况下 Redis 内存被写满（比如窗口是 1 天，2 天后这些 Key 自动消亡）。
local expire_time = math.ceil(interval * 2 / 1000)
if expire_time < 1 then expire_time = 1 end
redis.call("expire", value_key, expire_time)
redis.call("expire", permits_key, expire_time)

return 1
