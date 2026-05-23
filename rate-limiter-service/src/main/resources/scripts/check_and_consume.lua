-- Token-bucket rate limiter implemented as an atomic Redis Lua script.
-- Running inside Redis guarantees all reads and writes are atomic — no race conditions.
--
-- KEYS[1] = unique per-client key, e.g. "rate:limiter:192.168.1.1"
-- ARGV[1] = bucket_capacity        (max tokens in the bucket)
-- ARGV[2] = refill_rate_per_second (tokens added per second)
-- ARGV[3] = current_time           (Unix timestamp in seconds)
-- ARGV[4] = requested_tokens       (cost of this request in tokens)

local user_key             = KEYS[1]
local bucket_capacity      = tonumber(ARGV[1])
local refill_rate_per_second = tonumber(ARGV[2])
local current_time         = tonumber(ARGV[3])
local requested_tokens     = tonumber(ARGV[4])

-- Read the current bucket state (tokens remaining + last refill timestamp)
local current_state    = redis.call('HMGET', user_key, 'tokens', 'timestamp')
local last_tokens      = tonumber(current_state[1])
local last_refill_time = tonumber(current_state[2])

-- First request from this client: initialise a full bucket
if last_tokens == nil then
    last_tokens      = bucket_capacity
    last_refill_time = current_time
end

-- Calculate how many whole-second ticks have elapsed since the last refill
local elapsed_time  = math.max(0, current_time - last_refill_time)
local tokens_to_add = math.floor(elapsed_time * refill_rate_per_second)

-- Refill the bucket, capped at bucket_capacity
local new_tokens = math.min(last_tokens + tokens_to_add, bucket_capacity)

-- Advance the timestamp only by the ticks we actually consumed, preserving
-- any fractional remainder so tokens accumulate correctly over time.
local ticks_consumed = math.floor(tokens_to_add / refill_rate_per_second)
local new_refill_time = last_refill_time + ticks_consumed

local allowed = 0 -- 0 = deny
if new_tokens >= requested_tokens then
    new_tokens = new_tokens - requested_tokens
    allowed    = 1 -- 1 = allow
end

-- Persist updated state atomically
redis.call('HMSET', user_key, 'tokens', new_tokens, 'timestamp', new_refill_time)

-- Set TTL so stale keys are auto-evicted: time to fully refill + 60s buffer
local expire_time = math.ceil(bucket_capacity / refill_rate_per_second) + 60
redis.call('EXPIRE', user_key, expire_time)

-- Return 1 if allowed, 0 if denied
return allowed