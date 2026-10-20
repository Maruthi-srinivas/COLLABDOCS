package com.collabdocs.api.auth;

import com.collabdocs.common.ApiException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SessionDenylist {
    private final StringRedisTemplate redis;

    public SessionDenylist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void deny(UUID sessionId) {
        try {
            redis.opsForValue().set("denylist:session:" + sessionId, "1", Duration.ofMinutes(15));
        } catch (RuntimeException exception) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Try again shortly.");
        }
    }

    public void check(UUID sessionId) {
        try {
            if (Boolean.TRUE.equals(redis.hasKey("denylist:session:" + sessionId))) {
                throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Try again shortly.");
        }
    }
}
