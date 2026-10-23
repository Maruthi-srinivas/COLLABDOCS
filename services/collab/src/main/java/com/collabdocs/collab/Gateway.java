package com.collabdocs.collab;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.AppMetrics;
import com.collabdocs.common.DocumentRole;
import com.collabdocs.common.Ids;
import com.collabdocs.persistence.ContentUpdateService;
import com.collabdocs.persistence.DocumentAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class Gateway extends TextWebSocketHandler {
    private static final Map<String, WebSocketSession> LIVE = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final GatewayJwt jwt;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final DocumentAccessService access;
    private final ContentUpdateService updates;
    private final DocumentFanout fanout;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();

    public Gateway(ObjectMapper mapper, GatewayJwt jwt, StringRedisTemplate redis, JdbcTemplate jdbc,
                   DocumentAccessService access, ContentUpdateService updates, DocumentFanout fanout) {
        this.mapper = mapper;
        this.jwt = jwt;
        this.redis = redis;
        this.jdbc = jdbc;
        this.access = access;
        this.updates = updates;
        this.fanout = fanout;
        clock.scheduleAtFixedRate(this::recheck, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        LIVE.put(session.getId(), session);
        AppMetrics.INSTANCE.gauge("collab_sockets_open", LIVE.size());
        session.getAttributes().put("openedAt", System.currentTimeMillis());
        session.getAttributes().put("seenAt", System.currentTimeMillis());
        clock.schedule(() -> {
            if (session.isOpen() && session.getAttributes().get("client") == null) {
                error(session, "UNAUTHENTICATED", "Sign in again.");
                close(session, 4401, "Authentication timed out.");
            }
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.getAttributes().put("seenAt", System.currentTimeMillis());
        if (message.getPayloadLength() > 400000) {
            error(session, "PAYLOAD_TOO_LARGE", "Frame is too large.");
            close(session, 4413, "Frame is too large.");
            return;
        }
        JsonNode frame;
        try {
            frame = mapper.readTree(message.getPayload());
        } catch (Exception exception) {
            error(session, "INVALID_MESSAGE", "Message is not JSON.");
            return;
        }
        String type = frame.path("type").asText();
        JsonNode payload = frame.path("payload");
        ClientSession client = (ClientSession) session.getAttributes().get("client");
        if (client == null) {
            if (!"auth".equals(type)) {
                error(session, "UNAUTHENTICATED", "Authenticate first.");
                close(session, 4401, "Authenticate first.");
                return;
            }
            authenticate(session, payload);
            return;
        }
        switch (type) {
            case "ping" -> pong(session, payload);
            case "sync.update" -> append(session, client, payload);
            case "sync.catchup" -> catchup(session, client, payload.path("afterSeq").asLong(0));
            case "awareness" -> awareness(session, client, payload);
            case "bye" -> close(session, 1000, "bye");
            default -> error(session, "INVALID_MESSAGE", "Unknown message.");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        LIVE.remove(session.getId());
        AppMetrics.INSTANCE.gauge("collab_sockets_open", LIVE.size());
        ClientSession client = (ClientSession) session.getAttributes().get("client");
        if (client != null) {
            fanout.leave(client.documentId(), session);
            redis.delete("presence:" + client.documentId() + ":" + client.connectionId());
        }
    }

    private void authenticate(WebSocketSession session, JsonNode payload) {
        try {
            String intent = payload.path("intent").asText();
            if (!"edit".equals(intent) && !"view".equals(intent) && !"suggest".equals(intent)) {
                error(session, "INVALID_MESSAGE", "Intent is not supported.");
                close(session, 4400, "Intent is not supported.");
                return;
            }
            GatewayJwt.Access token = jwt.parse(payload.path("accessToken").asText());
            if (Boolean.TRUE.equals(redis.hasKey("denylist:session:" + token.sessionId()))) {
                AppMetrics.INSTANCE.increment("collab_auth_failures_total", "reason", "revoked");
                error(session, "UNAUTHENTICATED", "Sign in again.");
                close(session, 4401, "Sign in again.");
                return;
            }
            UUID documentId = UUID.fromString(payload.path("documentId").asText());
            DocumentRole role = access.resolve(token.userId(), documentId);
            if ("edit".equals(intent) && !role.atLeast(DocumentRole.EDITOR)) {
                error(session, "FORBIDDEN", "You cannot edit this document.");
                close(session, 4403, "You cannot edit this document.");
                return;
            }
            if ("suggest".equals(intent) && !role.atLeast(DocumentRole.COMMENTER)) {
                error(session, "FORBIDDEN", "You cannot suggest on this document.");
                close(session, 4403, "You cannot suggest on this document.");
                return;
            }
            String name = jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, token.userId());
            ClientSession client = new ClientSession(Ids.uuidV7(), token.userId(), token.sessionId(), documentId,
                    UUID.fromString(payload.path("clientId").asText()), payload.path("epoch").asInt(1), intent, role, name);
            session.getAttributes().put("client", client);
            fanout.join(documentId, session);
            welcome(session, client, payload.path("lastAckSeq").asLong(-1));
        } catch (ApiException exception) {
            int code = exception.status() == 401 ? 4401 : 4403;
            error(session, exception.code(), exception.getMessage());
            close(session, code, exception.getMessage());
        } catch (RuntimeException exception) {
            error(session, "INVALID_MESSAGE", "Authentication payload is invalid.");
            close(session, 4400, "Authentication payload is invalid.");
        }
    }

    private void welcome(WebSocketSession session, ClientSession client, long lastAckSeq) {
        var meta = jdbc.queryForMap("SELECT epoch, schema_version FROM documents WHERE id = ?", client.documentId());
        int epoch = ((Number) meta.get("epoch")).intValue();
        var snapshots = jdbc.query(
                """
                SELECT through_seq, state FROM document_snapshots
                WHERE document_id = ? AND epoch = ? AND state IS NOT NULL
                ORDER BY through_seq DESC LIMIT 1
                """,
                (rs, row) -> new Snapshot(rs.getLong("through_seq"), rs.getBytes("state")), client.documentId(), epoch);
        long through = snapshots.isEmpty() ? 0 : snapshots.get(0).through();
        long after = lastAckSeq >= through && lastAckSeq <= currentSeq(client.documentId(), epoch) ? lastAckSeq : through;
        boolean includeSnapshot = after <= through;
        List<Map<String, Object>> tail = jdbc.query(
                "SELECT seq, payload FROM collab_updates WHERE document_id = ? AND epoch = ? AND seq > ? ORDER BY seq LIMIT 500",
                (rs, row) -> Map.of("seq", rs.getLong("seq"), "payloadBase64", Base64.getEncoder().encodeToString(rs.getBytes("payload"))),
                client.documentId(), epoch, after);
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "welcome");
        ObjectNode body = frame.putObject("payload");
        body.put("connectionId", client.connectionId().toString());
        body.put("documentId", client.documentId().toString());
        body.put("epoch", epoch);
        body.put("schemaVersion", ((Number) meta.get("schema_version")).intValue());
        body.put("role", client.role().name().toLowerCase());
        body.put("throughSeq", through);
        if (includeSnapshot) {
            byte[] state = snapshots.isEmpty() ? new byte[0] : snapshots.get(0).state();
            body.put("snapshotBase64", Base64.getEncoder().encodeToString(state));
        }
        body.set("updates", mapper.valueToTree(tail));
        send(session, frame);
        touchPresence(client);
    }

    static int openSockets() {
        return LIVE.size();
    }

    private void append(WebSocketSession session, ClientSession client, JsonNode payload) {
        String updateId = payload.path("updateId").asText();
        long started = System.nanoTime();
        try {
            String updateIntent = payload.path("intent").asText("edit");
            if (!client.allows(updateIntent)) {
                nack(session, updateId, "FORBIDDEN", "Viewers cannot edit.");
                return;
            }
            byte[] raw = Base64.getDecoder().decode(payload.path("payloadBase64").asText());
            var ack = updates.append(new ContentUpdateService.AppendCommand(
                    client.documentId(), client.userId(), client.clientId(), UUID.fromString(updateId),
                    payload.path("epoch").asInt(), updateIntent, raw));
            ObjectNode frame = mapper.createObjectNode();
            frame.put("type", "sync.ack");
            ObjectNode body = frame.putObject("payload");
            body.put("updateId", updateId);
            body.put("epoch", ack.epoch());
            body.put("seq", ack.seq());
            body.put("duplicate", ack.duplicate());
            send(session, frame);
            AppMetrics.INSTANCE.observe("collab_update_ack_seconds", (System.nanoTime() - started) / 1_000_000_000d,
                    "result", ack.duplicate() ? "duplicate" : "ack");
            AppMetrics.INSTANCE.increment("collab_updates_total", "result", ack.duplicate() ? "duplicate" : "ack");
            if (!ack.duplicate()) {
                fanout.publishUpdate(client.documentId(), client.connectionId(), ack.epoch(), ack.seq(), client.userId(), raw);
            }
        } catch (ApiException exception) {
            AppMetrics.INSTANCE.increment("collab_updates_total", "result", "nack");
            String code = exception.status() == 409 && "STALE_EPOCH".equals(exception.code()) ? "STALE_EPOCH" : exception.code();
            nack(session, updateId, code, exception.getMessage());
            if ("STALE_EPOCH".equals(code)) close(session, 4409, exception.getMessage());
            if (exception.status() == 413) close(session, 4413, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            nack(session, updateId, "INVALID_MESSAGE", "Update payload is not valid base64.");
        }
    }

    private void catchup(WebSocketSession session, ClientSession client, long afterSeq) {
        var rows = jdbc.query(
                "SELECT seq, payload, actor_user_id FROM collab_updates WHERE document_id = ? AND epoch = ? AND seq > ? ORDER BY seq LIMIT 500",
                (rs, row) -> Map.of("seq", rs.getLong(1), "payload", rs.getBytes(2), "actor", rs.getObject(3, UUID.class)),
                client.documentId(), client.epoch(), afterSeq);
        for (var row : rows) {
            ObjectNode frame = mapper.createObjectNode();
            frame.put("type", "sync.apply");
            ObjectNode body = frame.putObject("payload");
            body.put("seq", ((Number) row.get("seq")).longValue());
            body.put("epoch", client.epoch());
            body.put("actorId", row.get("actor").toString());
            body.put("payloadBase64", Base64.getEncoder().encodeToString((byte[]) row.get("payload")));
            send(session, frame);
        }
    }

    private void awareness(WebSocketSession session, ClientSession client, JsonNode payload) {
        long now = System.currentTimeMillis();
        Long previous = (Long) session.getAttributes().get("awarenessAt");
        int count = previous != null && now - previous < 1000 ? (Integer) session.getAttributes().getOrDefault("awarenessCount", 0) : 0;
        if (count >= 4) return;
        session.getAttributes().put("awarenessAt", now);
        session.getAttributes().put("awarenessCount", count + 1);
        String color = payload.path("color").asText("#1D4ED8");
        int anchor = payload.path("anchor").asInt(0);
        int head = payload.path("head").asInt(anchor);
        fanout.publishAwareness(client.documentId(), client.connectionId(), client.userId(), client.displayName(), color, anchor, head);
        touchPresence(client);
    }

    private void pong(WebSocketSession session, JsonNode payload) {
        ClientSession client = (ClientSession) session.getAttributes().get("client");
        if (client != null) touchPresence(client);
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "pong");
        frame.putObject("payload").put("ts", payload.path("ts").asLong());
        send(session, frame);
    }

    private void touchPresence(ClientSession client) {
        redis.opsForValue().set("presence:" + client.documentId() + ":" + client.connectionId(), client.displayName(), Duration.ofSeconds(30));
    }

    private long currentSeq(UUID documentId, int epoch) {
        Long seq = jdbc.queryForObject(
                "SELECT last_seq FROM document_epochs WHERE document_id = ? AND epoch = ?", Long.class, documentId, epoch);
        return seq == null ? 0 : seq;
    }

    private void recheck() {
        long now = System.currentTimeMillis();
        for (WebSocketSession session : List.copyOf(LIVE.values())) {
            ClientSession client = (ClientSession) session.getAttributes().get("client");
            Long seen = (Long) session.getAttributes().get("seenAt");
            if (seen != null && now - seen > 45_000) {
                close(session, 1001, "Idle timeout.");
                continue;
            }
            if (client == null) continue;
            try {
                if (Boolean.TRUE.equals(redis.hasKey("denylist:session:" + client.sessionId()))) {
                    error(session, "UNAUTHENTICATED", "Sign in again.");
                    close(session, 4401, "Sign in again.");
                    continue;
                }
                DocumentRole role = access.resolve(client.userId(), client.documentId());
                boolean lostEdit = "edit".equals(client.intent()) && !role.atLeast(DocumentRole.EDITOR);
                boolean lostSuggest = "suggest".equals(client.intent()) && !role.atLeast(DocumentRole.COMMENTER);
                if (lostEdit || lostSuggest) {
                    error(session, "FORBIDDEN", "Access changed.");
                    close(session, 4403, "Access changed.");
                }
            } catch (ApiException exception) {
                error(session, exception.code(), exception.getMessage());
                close(session, exception.status() == 401 ? 4401 : 4403, exception.getMessage());
            } catch (RuntimeException ignored) {
                close(session, 4500, "Access check failed.");
            }
        }
    }

    private void nack(WebSocketSession session, String updateId, String code, String message) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "sync.nack");
        ObjectNode body = frame.putObject("payload");
        body.put("updateId", updateId);
        body.put("code", code);
        body.put("message", message);
        send(session, frame);
    }

    private void error(WebSocketSession session, String code, String message) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "error");
        ObjectNode body = frame.putObject("payload");
        body.put("code", code);
        body.put("message", message);
        send(session, frame);
    }

    static void send(WebSocketSession session, ObjectNode frame) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(frame.toString()));
                }
            }
        } catch (IOException ignored) {
            close(session, 4500, "Send failed.");
        }
    }

    static void close(WebSocketSession session, int code, String reason) {
        try {
            if (session.isOpen()) session.close(new CloseStatus(code, reason));
        } catch (IOException ignored) {
            // The peer is already gone.
        }
    }

    private record Snapshot(long through, byte[] state) {}
}
