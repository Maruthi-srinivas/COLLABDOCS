package com.collabdocs.api.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class AclEvents {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AclEvents(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void publish(UUID documentId, UUID userId, String event, String role) {
        ObjectNode body = mapper.createObjectNode();
        body.put("kind", "acl");
        body.put("documentId", documentId.toString());
        body.put("userId", userId.toString());
        body.put("event", event);
        if (role != null) body.put("role", role);
        redis.convertAndSend("acl:doc:" + documentId, body.toString());
    }
}
