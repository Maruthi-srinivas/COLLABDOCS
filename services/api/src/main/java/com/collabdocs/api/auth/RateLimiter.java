package com.collabdocs.api.auth;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.AppMetrics;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
    private final StringRedisTemplate redis;
    private final boolean enabled;

    public RateLimiter(StringRedisTemplate redis,
                       @org.springframework.beans.factory.annotation.Value("${collabdocs.rate-limit.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    public void hit(String key, int limit, Duration window) {
        if (!enabled) {
            return;
        }
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, window);
            }
            if (count != null && count > limit) {
                String route = key.startsWith("ratelimit:") ? key.split(":")[1] : "unknown";
                AppMetrics.INSTANCE.increment("ratelimit_block_total", "route", route);
                throw new ApiException(429, "RATE_LIMITED", "Too many requests. Try again later.");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Try again shortly.");
        }
    }
}
