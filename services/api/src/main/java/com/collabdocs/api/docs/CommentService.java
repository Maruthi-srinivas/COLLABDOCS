package com.collabdocs.api.docs;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.DocumentRole;
import com.collabdocs.common.Ids;
import com.collabdocs.persistence.DocumentAccessService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CommentService {
    private final JdbcTemplate jdbc;
    private final DocumentAccessService access;
    private final NotificationService notifications;
    private final DocEvents events;
    private final String publicUrl;

    public CommentService(JdbcTemplate jdbc, DocumentAccessService access, NotificationService notifications,
                          DocEvents events, @Value("${collabdocs.app-public-url}") String publicUrl) {
        this.jdbc = jdbc;
        this.access = access;
        this.notifications = notifications;
        this.events = events;
        this.publicUrl = publicUrl;
    }

    public List<Map<String, Object>> list(UUID actor, UUID documentId, String status) {
        access.require(actor, documentId, DocumentRole.VIEWER);
        String filter = status == null || status.isBlank() ? "" : " AND t.status = ?";
        Object[] args = status == null || status.isBlank()
                ? new Object[] {documentId}
                : new Object[] {documentId, status};
        List<Map<String, Object>> threads = jdbc.query(
                """
                SELECT t.id, t.quoted_text, t.status, t.anchor_state, t.created_by, t.created_at,
                       encode(t.anchor_start, 'base64') AS anchor_start, encode(t.anchor_end, 'base64') AS anchor_end,
                       u.display_name
                FROM comment_threads t JOIN users u ON u.id = t.created_by
                WHERE t.document_id = ?
                """ + filter + " ORDER BY t.created_at",
                (rs, row) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    UUID threadId = rs.getObject("id", UUID.class);
                    body.put("id", threadId);
                    body.put("quotedText", rs.getString("quoted_text"));
                    body.put("status", rs.getString("status"));
                    body.put("anchorState", rs.getString("anchor_state"));
                    body.put("anchorStartBase64", rs.getString("anchor_start"));
                    body.put("anchorEndBase64", rs.getString("anchor_end"));
                    body.put("createdBy", rs.getObject("created_by", UUID.class));
                    body.put("authorName", rs.getString("display_name"));
                    body.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    body.put("comments", comments(threadId));
                    return body;
                }, args);
        return threads;
    }

    @Transactional
    public Map<String, Object> create(UUID actor, UUID documentId, String body, String quoted, String anchorStart,
                                      String anchorEnd, List<String> mentions) {
        access.require(actor, documentId, DocumentRole.COMMENTER);
        String text = requiredBody(body);
        if (quoted == null || quoted.isBlank() || quoted.length() > 500) {
            throw new ApiException(400, "VALIDATION_FAILED", "Select the text you want to discuss.");
        }
        byte[] start = anchor(anchorStart);
        byte[] end = anchor(anchorEnd);
        List<UUID> mentioned = mentionIds(documentId, mentions);
        UUID threadId = Ids.uuidV7();
        UUID commentId = Ids.uuidV7();
        jdbc.update(
                """
                INSERT INTO comment_threads
                  (id, document_id, anchor_start, anchor_end, quoted_text, status, created_by)
                VALUES (?, ?, ?, ?, ?, 'open', ?)
                """,
                threadId, documentId, start, end, quoted, actor);
        jdbc.update(
                "INSERT INTO comments (id, thread_id, author_id, body) VALUES (?, ?, ?, ?)",
                commentId, threadId, actor, text);
        notifyMentions(actor, documentId, threadId, mentioned);
        afterCommit(() -> events.commentChanged(documentId));
        return Map.of("id", threadId, "commentId", commentId, "status", "open");
    }

    @Transactional
    public Map<String, Object> reply(UUID actor, UUID threadId, String body, List<String> mentions) {
        UUID documentId = documentOf(threadId);
        access.require(actor, documentId, DocumentRole.COMMENTER);
        String text = requiredBody(body);
        UUID commentId = Ids.uuidV7();
        jdbc.update("INSERT INTO comments (id, thread_id, author_id, body) VALUES (?, ?, ?, ?)", commentId, threadId, actor, text);
        List<UUID> participants = jdbc.query(
                "SELECT DISTINCT author_id FROM comments WHERE thread_id = ? AND deleted_at IS NULL AND author_id <> ?",
                (rs, row) -> rs.getObject(1, UUID.class), threadId, actor);
        for (UUID participant : participants) {
            notifications.notify(participant, "comment_reply", Map.of("documentId", documentId.toString(), "threadId", threadId.toString()));
        }
        notifyMentions(actor, documentId, threadId, mentionIds(documentId, mentions));
        afterCommit(() -> events.commentChanged(documentId));
        return Map.of("id", commentId, "threadId", threadId);
    }

    @Transactional
    public void edit(UUID actor, UUID commentId, String body) {
        var rows = jdbc.query(
                "SELECT c.author_id, c.created_at, c.deleted_at, t.document_id FROM comments c JOIN comment_threads t ON t.id = c.thread_id WHERE c.id = ?",
                (rs, row) -> new CommentRef(rs.getObject("author_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("deleted_at") != null, rs.getObject("document_id", UUID.class)),
                commentId);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Comment is not available.");
        CommentRef comment = rows.get(0);
        access.require(actor, comment.documentId(), DocumentRole.COMMENTER);
        if (comment.deleted() || !comment.authorId().equals(actor)) {
            throw new ApiException(403, "FORBIDDEN", "You can edit only your own comment.");
        }
        if (Duration.between(comment.createdAt(), Instant.now()).toMinutes() >= 15) {
            throw new ApiException(409, "COMMENT_LOCKED", "The edit window has closed.");
        }
        jdbc.update("UPDATE comments SET body = ?, edited_at = now() WHERE id = ?", requiredBody(body), commentId);
        afterCommit(() -> events.commentChanged(comment.documentId()));
    }

    @Transactional
    public void delete(UUID actor, UUID commentId) {
        var rows = jdbc.query(
                """
                SELECT c.author_id, t.document_id, d.owner_id
                FROM comments c
                JOIN comment_threads t ON t.id = c.thread_id
                JOIN documents d ON d.id = t.document_id
                WHERE c.id = ? AND c.deleted_at IS NULL
                """,
                (rs, row) -> Map.of("author", rs.getObject("author_id", UUID.class), "document", rs.getObject("document_id", UUID.class),
                        "owner", rs.getObject("owner_id", UUID.class)),
                commentId);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Comment is not available.");
        UUID documentId = (UUID) rows.get(0).get("document");
        access.require(actor, documentId, DocumentRole.VIEWER);
        boolean owner = actor.equals(rows.get(0).get("owner"));
        if (!owner && !actor.equals(rows.get(0).get("author"))) {
            throw new ApiException(403, "FORBIDDEN", "You cannot delete this comment.");
        }
        jdbc.update("UPDATE comments SET deleted_at = now() WHERE id = ?", commentId);
        afterCommit(() -> events.commentChanged(documentId));
    }

    @Transactional
    public void resolve(UUID actor, UUID threadId, boolean open) {
        UUID documentId = documentOf(threadId);
        access.require(actor, documentId, DocumentRole.COMMENTER);
        jdbc.update(
                """
                UPDATE comment_threads
                SET status = ?, resolved_by = ?, resolved_at = CASE WHEN ? THEN NULL ELSE now() END
                WHERE id = ?
                """,
                open ? "open" : "resolved", open ? null : actor, open, threadId);
        afterCommit(() -> events.commentChanged(documentId));
    }

    @Transactional
    public void anchor(UUID actor, UUID threadId, String anchorState) {
        UUID documentId = documentOf(threadId);
        access.require(actor, documentId, DocumentRole.COMMENTER);
        if (!"bound".equals(anchorState) && !"detached".equals(anchorState)) {
            throw new ApiException(400, "VALIDATION_FAILED", "Anchor state is invalid.");
        }
        jdbc.update("UPDATE comment_threads SET anchor_state = ? WHERE id = ?", anchorState, threadId);
        afterCommit(() -> events.commentChanged(documentId));
    }

    private List<Map<String, Object>> comments(UUID threadId) {
        return jdbc.query(
                """
                SELECT c.id, c.author_id, c.body, c.created_at, c.edited_at, c.deleted_at, u.display_name
                FROM comments c JOIN users u ON u.id = c.author_id
                WHERE c.thread_id = ? ORDER BY c.created_at
                """,
                (rs, row) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", rs.getObject("id", UUID.class));
                    body.put("authorId", rs.getObject("author_id", UUID.class));
                    body.put("authorName", rs.getString("display_name"));
                    body.put("body", rs.getTimestamp("deleted_at") == null ? rs.getString("body") : "");
                    body.put("deleted", rs.getTimestamp("deleted_at") != null);
                    body.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    body.put("editedAt", rs.getTimestamp("edited_at") == null ? null : rs.getTimestamp("edited_at").toInstant().toString());
                    return body;
                }, threadId);
    }

    private void notifyMentions(UUID actor, UUID documentId, UUID threadId, List<UUID> mentions) {
        for (UUID mentioned : mentions) {
            if (mentioned.equals(actor)) continue;
            notifications.notify(mentioned, "mention", Map.of("documentId", documentId.toString(), "threadId", threadId.toString()));
            String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, mentioned);
            notifications.mail(email, "You were mentioned in CollabDocs", publicUrl + "/d/" + documentId);
        }
    }

    private List<UUID> mentionIds(UUID documentId, List<String> mentions) {
        if (mentions == null || mentions.isEmpty()) return List.of();
        if (mentions.size() > 20) throw new ApiException(400, "VALIDATION_FAILED", "A comment can mention at most 20 people.");
        List<UUID> ids = new ArrayList<>();
        for (String raw : mentions) {
            UUID userId = UUID.fromString(raw);
            try {
                access.require(userId, documentId, DocumentRole.VIEWER);
            } catch (ApiException exception) {
                throw new ApiException(422, "MENTION_FORBIDDEN", "Mention someone who can open the document.");
            }
            ids.add(userId);
        }
        return ids;
    }

    private UUID documentOf(UUID threadId) {
        List<UUID> ids = jdbc.query("SELECT document_id FROM comment_threads WHERE id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), threadId);
        if (ids.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Thread is not available.");
        return ids.get(0);
    }

    private static String requiredBody(String body) {
        if (body == null || body.isBlank() || body.length() > 5000) {
            throw new ApiException(400, "VALIDATION_FAILED", "Comment must be 1 to 5000 characters.");
        }
        return body.trim();
    }

    private static byte[] anchor(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value == null ? "" : value);
            if (decoded.length < 1 || decoded.length > 1024) throw new IllegalArgumentException("length");
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "VALIDATION_FAILED", "Anchor is not valid.");
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

    private record CommentRef(UUID authorId, Instant createdAt, boolean deleted, UUID documentId) {}
}
