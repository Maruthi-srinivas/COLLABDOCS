package com.collabdocs.api.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class DocEvents {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public DocEvents(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void commentChanged(UUID documentId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("kind", "comment");
        body.put("documentId", documentId.toString());
        redis.convertAndSend("collab:doc:" + documentId, body.toString());
    }

    public void restored(UUID documentId, int epoch, byte[] snapshot, long throughSeq) {
        ObjectNode body = mapper.createObjectNode();
        body.put("kind", "restored");
        body.put("documentId", documentId.toString());
        body.put("epoch", epoch);
        body.put("snapshotBase64", Base64.getEncoder().encodeToString(snapshot));
        body.put("throughSeq", throughSeq);
        redis.convertAndSend("collab:doc:" + documentId, body.toString());
    }

    public void update(UUID documentId, int epoch, long seq, UUID actorId, byte[] payload) {
        ObjectNode body = mapper.createObjectNode();
        body.put("kind", "update");
        body.put("documentId", documentId.toString());
        body.put("origin", "rest");
        body.put("epoch", epoch);
        body.put("seq", seq);
        body.put("actorId", actorId.toString());
        body.put("payloadBase64", Base64.getEncoder().encodeToString(payload));
        redis.convertAndSend("collab:doc:" + documentId, body.toString());
    }
}
