package com.collabdocs.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class DocumentFanout implements MessageListener {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Map<UUID, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public DocumentFanout(StringRedisTemplate redis, ObjectMapper mapper, RedisMessageListenerContainer container) {
        this.redis = redis;
        this.mapper = mapper;
        container.addMessageListener(this, new PatternTopic("collab:doc:*"));
        container.addMessageListener(this, new PatternTopic("acl:doc:*"));
    }

    public void join(UUID documentId, WebSocketSession session) {
        rooms.computeIfAbsent(documentId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void leave(UUID documentId, WebSocketSession session) {
        Set<WebSocketSession> room = rooms.get(documentId);
        if (room != null) room.remove(session);
    }

    public void publishUpdate(UUID documentId, UUID origin, int epoch, long seq, UUID actorId, byte[] payload) {
        ObjectNode body = mapper.createObjectNode();
        body.put("kind", "update");
        body.put("documentId", documentId.toString());
        body.put("origin", origin.toString());
        body.put("epoch", epoch);
        body.put("seq", seq);
        body.put("actorId", actorId.toString());
        body.put("payloadBase64", Base64.getEncoder().encodeToString(payload));
        redis.convertAndSend("collab:doc:" + documentId, body.toString());
    }

    public void publishAwareness(UUID documentId, UUID origin, UUID userId, String displayName, String color, int anchor, int head) {
        ObjectNode body = mapper.createObjectNode();
        body.put("kind", "awareness");
        body.put("documentId", documentId.toString());
        body.put("origin", origin.toString());
        body.put("connectionId", origin.toString());
        body.put("userId", userId.toString());
        body.put("displayName", displayName);
        body.put("color", color);
        body.put("anchor", anchor);
        body.put("head", head);
        redis.convertAndSend("collab:doc:" + documentId, body.toString());
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode body = mapper.readTree(message.getBody());
            UUID documentId = UUID.fromString(body.path("documentId").asText());
            Set<WebSocketSession> room = rooms.get(documentId);
            if (room == null) return;
            String kind = body.path("kind").asText();
            if ("update".equals(kind)) {
                ObjectNode frame = mapper.createObjectNode();
                frame.put("type", "sync.apply");
                ObjectNode payload = frame.putObject("payload");
                payload.put("seq", body.path("seq").asLong());
                payload.put("epoch", body.path("epoch").asInt());
                payload.put("actorId", body.path("actorId").asText());
                payload.put("payloadBase64", body.path("payloadBase64").asText());
                String origin = body.path("origin").asText();
                send(room, frame, origin);
            } else if ("awareness".equals(kind)) {
                ObjectNode frame = mapper.createObjectNode();
                frame.put("type", "awareness");
                ObjectNode payload = frame.putObject("payload");
                payload.put("connectionId", body.path("connectionId").asText());
                payload.put("userId", body.path("userId").asText());
                payload.put("displayName", body.path("displayName").asText());
                payload.put("color", body.path("color").asText());
                payload.put("anchor", body.path("anchor").asInt());
                payload.put("head", body.path("head").asInt());
                send(room, frame, body.path("origin").asText());
            } else if ("comment".equals(kind)) {
                ObjectNode frame = mapper.createObjectNode();
                frame.put("type", "comment.changed");
                frame.putObject("payload").put("documentId", documentId.toString());
                send(room, frame, "");
            } else if ("restored".equals(kind)) {
                ObjectNode frame = mapper.createObjectNode();
                frame.put("type", "document.restored");
                ObjectNode payload = frame.putObject("payload");
                payload.put("epoch", body.path("epoch").asInt());
                payload.put("snapshotBase64", body.path("snapshotBase64").asText());
                payload.put("throughSeq", body.path("throughSeq").asLong());
                send(room, frame, "");
            } else if ("acl".equals(kind)) {
                ObjectNode frame = mapper.createObjectNode();
                String event = body.path("event").asText();
                frame.put("type", "revoked".equals(event) ? "acl.revoked" : "acl.role");
                ObjectNode payload = frame.putObject("payload");
                payload.put("userId", body.path("userId").asText());
                if (body.has("role")) payload.put("role", body.path("role").asText());
                for (WebSocketSession session : room) {
                    ClientSession client = (ClientSession) session.getAttributes().get("client");
                    if (client != null && client.userId().toString().equals(body.path("userId").asText())) {
                        Gateway.send(session, frame);
                        if ("revoked".equals(event)) Gateway.close(session, 4403, "Access changed.");
                    }
                }
            }
        } catch (Exception ignored) {
            // A malformed fan-out message does not close local sockets.
        }
    }

    private void send(Set<WebSocketSession> room, ObjectNode frame, String origin) {
        for (WebSocketSession session : room) {
            ClientSession client = (ClientSession) session.getAttributes().get("client");
            if (client != null && !client.connectionId().toString().equals(origin)) {
                Gateway.send(session, frame);
            }
        }
    }
}
