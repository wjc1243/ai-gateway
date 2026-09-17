-- 令牌桶限流（分布式，原子）
--
-- KEYS[1] : 限流 key，例如 ratelimit:ip:127.0.0.1
-- ARGV[1] : rate     每秒生成的令牌数（长期速率）
-- ARGV[2] : capacity 桶容量（允许的突发上限）
-- ARGV[3] : now      当前时间戳（毫秒，由应用传入，避免依赖 Redis 服务器时钟）
-- ARGV[4] : requested 本次请求的令牌数（通常为 1；AI 场景可按预估 token 数传入）
--
-- 返回: { allowed, remaining }
--   allowed   = 1 放行 / 0 拒绝
--   remaining = 剩余令牌数（向下取整，用于响应头）

local key       = KEYS[1]
local rate      = tonumber(ARGV[1])
local capacity  = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

-- 1. 读取桶当前状态（hash 存两个字段：剩余令牌数、上次刷新时间）
local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

-- 2. 首次访问：桶初始为满
if tokens == nil or ts == nil then
    tokens = capacity
    ts     = now
end

-- 3. 按经过的时间补充令牌（核心：速率 × 时间）
--    elapsed 取 max(0, ...) 防止时钟回拨导致令牌被扣成负数
local elapsed = math.max(0, now - ts) / 1000.0
tokens = math.min(capacity, tokens + elapsed * rate)

-- 4. 判断并扣减
local allowed = 0
if tokens >= requested then
    tokens  = tokens - requested
    allowed = 1
end

-- 5. 写回状态
redis.call('HSET', key, 'tokens', tokens, 'ts', now)

-- 6. 设置过期：桶从空到填满所需时间的 2 倍
--    避免长期不活跃的 key 永久占用内存
local ttl = math.ceil(capacity / rate * 1000) * 2
redis.call('PEXPIRE', key, ttl)

return { allowed, math.floor(tokens) }
