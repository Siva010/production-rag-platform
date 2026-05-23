package dev.trafficguard.ratelimiterservice.service;

import dev.trafficguard.ratelimiterservice.config.RateLimiterProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;

@Service
public class RedisRateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> redisScript;
    private final RateLimiterProperties rateLimiterProperties;

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterService.class);

    /**
     * Constructs a RedisRateLimiterService with the given RedisTemplate, RedisScript, and rate limiter properties.
     * @param redisTemplate the Redis template used for executing Redis operations
     * @param redisScript the Redis script used for rate limiting logic
     * @param rateLimiterProperties configuration properties for rate limiting (bucket size, refill rate)
     */
    public RedisRateLimiterService(RedisTemplate<String, String> redisTemplate, RedisScript<Long> redisScript, RateLimiterProperties rateLimiterProperties) {
        this.redisTemplate = redisTemplate;
        this.redisScript = redisScript;
        this.rateLimiterProperties = rateLimiterProperties;
    }

    /**
     * Checks if the given key (e.g. client IP) is allowed to make a request, consuming 1 token by default.
     * @param key the key representing a client (for example, an IP address)
     * @return true if allowed, false if the rate limit has been exceeded
     */
    public boolean isAllowed(String key) {
        return isAllowed(key, 1L);
    }

    /**
     * Checks if a given key is allowed to make a request consuming a specified number of tokens.
     * <p>
     * This method executes an atomic Redis Lua script to enforce token-bucket rate limits.
     * It is decorated with a circuit breaker and retry to gracefully handle Redis unavailability.
     * @param key the key representing a client (for example, an IP address)
     * @param requestedTokens the number of tokens this request costs
     * @return true if allowed, false if the rate limit has been exceeded
     */
    @CircuitBreaker(name = "redis", fallbackMethod = "fallbackIsAllowed")
    @Retry(name = "redis")
    public boolean isAllowed(String key, long requestedTokens) {
        String userKey = "rate:limiter:" + key;
        long currentTime = Instant.now().getEpochSecond();
        // Convert per-minute refill rate to per-second for the Lua script
        double refillRatePerSecond = rateLimiterProperties.getRefillRatePerMinute() / 60.0;

        Long result = redisTemplate.execute(
                redisScript,
                Collections.singletonList(userKey),
                String.valueOf(rateLimiterProperties.getBucketCapacity()),
                String.valueOf(refillRatePerSecond),
                String.valueOf(currentTime),
                String.valueOf(requestedTokens)
        );

        // Guard against null result (e.g. script evaluation failure); default to deny
        return result != null && result == 1L;
    }

    /**
     * Fallback method invoked by the circuit breaker when Redis is unavailable or an error occurs.
     * <p>
     * Defaults to allowing the request (fail-open) to prevent cascading failures from blocking all traffic.
     * @param key the key representing a client (for example, an IP address)
     * @param requestedTokens the number of tokens that were requested
     * @param t the Throwable that triggered the fallback
     * @return true to allow the request despite the Redis failure
     */
    public boolean fallbackIsAllowed(String key, long requestedTokens, Throwable t) {
        log.error("Circuit breaker is open for key: {}. Falling back to fail-open. Reason: {}", key, t.getMessage());
        return true;
    }

}
