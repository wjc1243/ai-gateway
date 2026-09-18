local n         = #KEYS
local now       = tonumber(ARGV[1])
local requested = tonumber(ARGV[2])

local rate     = {}
local capacity = {}
for i = 1, n do
    rate[i]     = tonumber(ARGV[2 + (i - 1) * 2 + 1])
    capacity[i] = tonumber(ARGV[2 + (i - 1) * 2 + 2])
end

-- 阶段一：只算不写
local newTokens = {}
for i = 1, n do
    local bucket = redis.call('HMGET', KEYS[i], 'tokens', 'ts')
    local tokens = tonumber(bucket[1])
    local ts     = tonumber(bucket[2])
    if tokens == nil or ts == nil then
        tokens = capacity[i]
        ts     = now
    end
    local elapsed = math.max(0, now - ts) / 1000.0
    newTokens[i] = math.min(capacity[i], tokens + elapsed * rate[i])
end

-- 阶段二：全通过才继续
for i = 1, n do
    if newTokens[i] < requested then
        return { 0, -1, KEYS[i] }
    end
end

-- 阶段三：统一扣减
local minRemaining = newTokens[1] - requested
for i = 1, n do
    local tokens = newTokens[i] - requested
    redis.call('HSET', KEYS[i], 'tokens', tokens, 'ts', now)
    local ttl = math.ceil(capacity[i] / rate[i] * 1000) * 2
    if ttl < 3600000 then
        ttl = 3600000
    end
    redis.call('PEXPIRE', KEYS[i], ttl)
    if tokens < minRemaining then
        minRemaining = tokens
    end
end

return { 1, math.floor(minRemaining), "" }