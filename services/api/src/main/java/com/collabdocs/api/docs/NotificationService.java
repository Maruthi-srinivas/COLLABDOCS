package com.collabdocs.api.docs;

import com.collabdocs.api.auth.MailPort;
import com.collabdocs.common.ApiException;
import com.collabdocs.common.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MailPort mail;

    public NotificationService(JdbcTemplate jdbc, ObjectMapper mapper, MailPort mail) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.mail = mail;
    }

    public void notify(UUID userId, String type, Map<String, ?> payload) {
        if (userId == null) return;
        try {
            jdbc.update(
                    "INSERT INTO notifications (id, user_id, type, payload) VALUES (?, ?, ?, ?::jsonb)",
                    Ids.uuidV7(), userId, type, mapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public void mail(String email, String subject, String text) {
        if (email != null && !email.isBlank()) mail.send(email, subject, text);
    }

    public List<Map<String, Object>> list(UUID userId, boolean unread) {
        String sql = unread
                ? "SELECT id, type, payload::text, read_at, created_at FROM notifications WHERE user_id = ? AND read_at IS NULL ORDER BY created_at DESC LIMIT 50"
                : "SELECT id, type, payload::text, read_at, created_at FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT 50";
        return jdbc.query(sql, (rs, row) -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", rs.getObject("id", UUID.class));
            body.put("type", rs.getString("type"));
            body.put("payload", rs.getString("payload"));
            body.put("readAt", rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant().toString());
            body.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return body;
        }, userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        int updated = jdbc.update(
                "UPDATE notifications SET read_at = now() WHERE id = ? AND user_id = ? AND read_at IS NULL",
                notificationId, userId);
        if (updated == 0) {
            Integer owned = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE id = ? AND user_id = ?",
                    Integer.class, notificationId, userId);
            if (owned == null || owned == 0) throw new ApiException(404, "NOT_FOUND", "Notification is not available.");
        }
    }

    @Transactional
    public void markAllRead(UUID userId) {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE user_id = ? AND read_at IS NULL", userId);
    }
}
