package com.collabdocs.persistence;

import com.collabdocs.common.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentUpdateService {
    public record AppendCommand(UUID documentId, UUID actorId, UUID clientId, UUID updateId, int epoch,
                                String intent, byte[] payload) {}

    public record Ack(int epoch, long seq, boolean duplicate) {}

    private final JdbcTemplate jdbc;

    public ContentUpdateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Ack append(AppendCommand command) {
        if (command.payload().length < 1 || command.payload().length > 262144) {
            throw new ApiException(413, "PAYLOAD_TOO_LARGE", "Update exceeds the size limit.");
        }
        var locked = jdbc.query(
                """
                SELECT d.status, e.epoch
                FROM documents d
                JOIN document_epochs e ON e.document_id = d.id AND e.epoch = d.epoch
                WHERE d.id = ?
                FOR UPDATE OF e
                """,
                (rs, row) -> new Locked(rs.getString("status"), rs.getInt("epoch")),
                command.documentId());
        if (locked.isEmpty()) {
            throw new ApiException(404, "NOT_FOUND", "Document is not available.");
        }
        Locked row = locked.get(0);
        if (!"active".equals(row.status())) {
            throw new ApiException(409, "DOCUMENT_NOT_EDITABLE", "This document cannot be edited.");
        }
        if (row.epoch() != command.epoch()) {
            throw new ApiException(409, "STALE_EPOCH", "The document was restored. Reload to continue.");
        }
        var existing = jdbc.query(
                "SELECT seq FROM collab_updates WHERE document_id = ? AND client_id = ? AND update_id = ?",
                (rs, n) -> rs.getLong("seq"), command.documentId(), command.clientId(), command.updateId());
        if (!existing.isEmpty()) {
            return new Ack(row.epoch(), existing.get(0), true);
        }
        Long seq = jdbc.queryForObject(
                """
                UPDATE document_epochs SET last_seq = last_seq + 1
                WHERE document_id = ? AND epoch = ?
                RETURNING last_seq
                """,
                Long.class, command.documentId(), row.epoch());
        jdbc.update(
                """
                INSERT INTO collab_updates
                  (document_id, epoch, seq, client_id, update_id, actor_user_id, intent, payload, byte_size)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                command.documentId(), row.epoch(), seq, command.clientId(), command.updateId(),
                command.actorId(), command.intent(), command.payload(), command.payload().length);
        jdbc.update(
                "UPDATE documents SET last_edited_by = ?, last_edited_at = now(), updated_at = now() WHERE id = ?",
                command.actorId(), command.documentId());
        return new Ack(row.epoch(), seq, false);
    }

    private record Locked(String status, int epoch) {}
}
