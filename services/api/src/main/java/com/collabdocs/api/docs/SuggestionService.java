package com.collabdocs.api.docs;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.DocumentRole;
import com.collabdocs.persistence.ContentUpdateService;
import com.collabdocs.persistence.DocumentAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SuggestionService {
    private final JdbcTemplate jdbc;
    private final DocumentAccessService access;
    private final ContentUpdateService updates;
    private final NotificationService notifications;
    private final DocEvents events;
    private final ObjectMapper mapper;

    public SuggestionService(JdbcTemplate jdbc, DocumentAccessService access, ContentUpdateService updates,
                             NotificationService notifications, DocEvents events, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.updates = updates;
        this.notifications = notifications;
        this.events = events;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> list(UUID actor, UUID documentId, String status) {
        access.require(actor, documentId, DocumentRole.VIEWER);
        String filter = status == null || status.isBlank() ? "" : " AND s.status = ?";
        Object[] args = status == null || status.isBlank() ? new Object[] {documentId} : new Object[] {documentId, status};
        return jdbc.query(
                """
                SELECT s.id, s.author_id, s.type, s.status, s.payload::text, s.anchor_state, s.created_at,
                       s.resolved_at, s.resolved_by, u.display_name
                FROM suggestions s JOIN users u ON u.id = s.author_id
                WHERE s.document_id = ?
                """ + filter + " ORDER BY s.created_at",
                (rs, row) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", rs.getObject("id", UUID.class));
                    body.put("authorId", rs.getObject("author_id", UUID.class));
                    body.put("authorName", rs.getString("display_name"));
                    body.put("type", rs.getString("type"));
                    body.put("status", rs.getString("status"));
                    body.put("payload", rs.getString("payload"));
                    body.put("anchorState", rs.getString("anchor_state"));
                    body.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    body.put("resolvedAt", rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant().toString());
                    body.put("resolvedBy", rs.getObject("resolved_by", UUID.class));
                    return body;
                }, args);
    }

    @Transactional
    public boolean register(UUID actor, UUID documentId, UUID suggestionId, String type, Object payload) {
        access.require(actor, documentId, DocumentRole.COMMENTER);
        if (!"insert".equals(type) && !"delete".equals(type) && !"format".equals(type)) {
            throw new ApiException(400, "VALIDATION_FAILED", "Suggestion type is invalid.");
        }
        String json = payloadJson(payload);
        int inserted = jdbc.update(
                """
                INSERT INTO suggestions (id, document_id, author_id, type, status, payload)
                VALUES (?, ?, ?, ?, 'pending', ?::jsonb)
                ON CONFLICT (id) DO NOTHING
                """,
                suggestionId, documentId, actor, type, json);
        if (inserted == 1) return true;
        Integer same = jdbc.queryForObject(
                "SELECT COUNT(*) FROM suggestions WHERE id = ? AND document_id = ?",
                Integer.class, suggestionId, documentId);
        if (same != null && same > 0) return false;
        throw new ApiException(409, "CONFLICT", "That suggestion id belongs to another document.");
    }

    @Transactional
    public void anchor(UUID actor, UUID documentId, UUID suggestionId, String anchorState) {
        access.require(actor, documentId, DocumentRole.COMMENTER);
        if (!"bound".equals(anchorState) && !"detached".equals(anchorState)) {
            throw new ApiException(400, "VALIDATION_FAILED", "Anchor state is invalid.");
        }
        int updated = jdbc.update(
                "UPDATE suggestions SET anchor_state = ? WHERE id = ? AND document_id = ? AND status = 'pending'",
                anchorState, suggestionId, documentId);
        if (updated == 0) throw new ApiException(404, "NOT_FOUND", "Suggestion is not available.");
    }

    @Transactional
    public Map<String, Object> accept(UUID actor, UUID documentId, UUID suggestionId, Map<String, Object> update, boolean accept) {
        access.require(actor, documentId, DocumentRole.EDITOR);
        byte[] raw = payload(update);
        var locked = jdbc.query(
                "SELECT author_id FROM suggestions WHERE id = ? AND document_id = ? AND status = 'pending' FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), suggestionId, documentId);
        if (locked.isEmpty()) throw new ApiException(409, "SUGGESTION_RESOLVED", "This suggestion was already resolved.");
        ContentUpdateService.Ack ack = updates.append(new ContentUpdateService.AppendCommand(
                documentId, actor, UUID.fromString(String.valueOf(update.get("clientId"))),
                UUID.fromString(String.valueOf(update.get("updateId"))),
                ((Number) update.get("epoch")).intValue(), "edit", raw));
        jdbc.update(
                "UPDATE suggestions SET status = ?, resolved_at = now(), resolved_by = ? WHERE id = ?",
                accept ? "accepted" : "rejected", actor, suggestionId);
        UUID author = locked.get(0);
        if (!author.equals(actor)) {
            notifications.notify(author, "suggestion_resolved", Map.of(
                    "documentId", documentId.toString(), "suggestionId", suggestionId.toString(), "status", accept ? "accepted" : "rejected"));
        }
        if (!ack.duplicate()) {
            afterCommit(() -> events.update(documentId, ack.epoch(), ack.seq(), actor, raw));
        }
        return Map.of("id", suggestionId, "status", accept ? "accepted" : "rejected", "seq", ack.seq(), "epoch", ack.epoch());
    }

    @Transactional
    public Map<String, Object> acceptAll(UUID actor, UUID documentId, List<Map<String, Object>> batch, boolean accept) {
        access.require(actor, documentId, DocumentRole.EDITOR);
        List<UUID> pending = jdbc.query(
                "SELECT id FROM suggestions WHERE document_id = ? AND status = 'pending' ORDER BY created_at FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), documentId);
        int count = 0;
        for (UUID suggestionId : pending) {
            Map<String, Object> update = batch.stream()
                    .filter((item) -> suggestionId.toString().equals(String.valueOf(item.get("suggestionId"))))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(400, "VALIDATION_FAILED", "Each pending suggestion needs an update."));
            accept(actor, documentId, suggestionId, update, accept);
            count += 1;
        }
        return Map.of("count", count);
    }

    private byte[] payload(Map<String, Object> update) {
        try {
            byte[] raw = Base64.getDecoder().decode(String.valueOf(update.get("payloadBase64")));
            if (raw.length < 1) throw new IllegalArgumentException("empty");
            return raw;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "VALIDATION_FAILED", "Suggestion resolution needs a content update.");
        }
    }

    private String payloadJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new ApiException(400, "VALIDATION_FAILED", "Suggestion payload is not valid.");
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
