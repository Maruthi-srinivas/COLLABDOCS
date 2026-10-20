package com.collabdocs.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountPurgeService {
    private final JdbcTemplate jdbc;
    private final DocumentPurgeService documents;
    private final ObjectStorage storage;

    public AccountPurgeService(JdbcTemplate jdbc, DocumentPurgeService documents, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.storage = storage;
    }

    @Transactional
    public int purgeDue() {
        List<UUID> users = jdbc.query(
                """
                SELECT id FROM users
                WHERE status = 'pending_deletion' AND purge_after IS NOT NULL AND purge_after <= now()
                """,
                (rs, row) -> rs.getObject(1, UUID.class));
        int purged = 0;
        for (UUID userId : users) {
            if (blockedByWorkspace(userId)) {
                continue;
            }
            List<UUID> owned = jdbc.query("SELECT id FROM documents WHERE owner_id = ?",
                    (rs, row) -> rs.getObject(1, UUID.class), userId);
            owned.forEach(documents::purge);
            String avatar = jdbc.query("SELECT avatar_object_key FROM users WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, userId);
            if (avatar != null) {
                storage.delete(avatar);
            }
            jdbc.update("DELETE FROM refresh_sessions WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM email_tokens WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
            jdbc.update(
                    """
                    UPDATE users
                    SET email = ?, display_name = 'Deleted user', password_hash = NULL,
                        avatar_object_key = NULL, status = 'deleted', deleted_at = now(),
                        purge_after = NULL, updated_at = now()
                    WHERE id = ?
                    """,
                    "deleted+" + userId + "@invalid.collabdocs.local", userId);
            purged++;
        }
        return purged;
    }

    private boolean blockedByWorkspace(UUID userId) {
        Boolean blocked = jdbc.queryForObject(
                """
                SELECT EXISTS(
                  SELECT 1 FROM workspaces w
                  JOIN workspace_memberships others
                    ON others.workspace_id = w.id AND others.user_id <> w.owner_id
                  WHERE w.owner_id = ?
                )
                """,
                Boolean.class, userId);
        return Boolean.TRUE.equals(blocked);
    }
}
