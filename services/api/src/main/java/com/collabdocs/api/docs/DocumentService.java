package com.collabdocs.api.docs;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.DocumentRole;
import com.collabdocs.common.Ids;
import com.collabdocs.persistence.ContentUpdateService;
import com.collabdocs.persistence.DocumentAccessService;
import com.collabdocs.persistence.DocumentPurgeService;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {
    private final JdbcTemplate jdbc;
    private final DocumentAccessService access;
    private final ContentUpdateService updates;
    private final DocumentPurgeService purge;
    private final byte[] emptySnapshot;

    public DocumentService(JdbcTemplate jdbc, DocumentAccessService access, ContentUpdateService updates,
                           DocumentPurgeService purge) throws IOException {
        this.jdbc = jdbc;
        this.access = access;
        this.updates = updates;
        this.purge = purge;
        this.emptySnapshot = new ClassPathResource("collab/empty-doc-v1.bin").getContentAsByteArray();
    }

    @Transactional
    public Map<String, Object> create(UUID userId, UUID workspaceId, String title, UUID folderId) {
        access.requireWorkspaceMember(userId, workspaceId);
        if (folderId != null) {
            requireFolder(workspaceId, folderId);
        }
        UUID id = Ids.uuidV7();
        String name = title == null || title.isBlank() ? "Untitled document" : checkTitle(title);
        jdbc.update(
                """
                INSERT INTO documents (id, workspace_id, folder_id, owner_id, title)
                VALUES (?, ?, ?, ?, ?)
                """,
                id, workspaceId, folderId, userId, name);
        jdbc.update("INSERT INTO document_epochs (document_id, epoch) VALUES (?, 1)", id);
        jdbc.update(
                """
                INSERT INTO document_snapshots
                  (id, document_id, epoch, through_seq, schema_version, kind, byte_size, state, created_by)
                VALUES (?, ?, 1, 0, 1, 'base', ?, ?, ?)
                """,
                Ids.uuidV7(), id, emptySnapshot.length, emptySnapshot, userId);
        jdbc.update(
                """
                INSERT INTO document_permissions (id, document_id, principal_type, user_id, role, granted_by)
                VALUES (?, ?, 'user', ?, 'owner', ?)
                """,
                Ids.uuidV7(), id, userId, userId);
        return get(userId, id);
    }

    public Map<String, Object> get(UUID userId, UUID documentId) {
        DocumentRole role = access.require(userId, documentId, DocumentRole.VIEWER);
        return one(userId, documentId, role);
    }

    public Map<String, Object> list(UUID userId, UUID workspaceId, UUID folderId, String sort, String direction, int limit) {
        access.requireWorkspaceMember(userId, workspaceId);
        String column = switch (sort == null ? "updatedAt" : sort) {
            case "title" -> "d.title";
            case "createdAt" -> "d.created_at";
            default -> "d.updated_at";
        };
        String dir = "asc".equalsIgnoreCase(direction) ? "ASC" : "DESC";
        int size = Math.min(Math.max(limit <= 0 ? 50 : limit, 1), 100);
        String folderClause = folderId == null ? "d.folder_id IS NULL" : "d.folder_id = ?";
        String sql = """
                SELECT d.*, p.role, EXISTS(SELECT 1 FROM document_stars s WHERE s.document_id = d.id AND s.user_id = ?) AS starred
                FROM documents d
                JOIN document_permissions p ON p.document_id = d.id AND p.user_id = ? AND p.revoked_at IS NULL
                WHERE d.workspace_id = ? AND d.status = 'active' AND %s
                ORDER BY %s %s, d.id DESC
                LIMIT ?
                """.formatted(folderClause, column, dir);
        Object[] args = folderId == null
                ? new Object[]{userId, userId, workspaceId, size}
                : new Object[]{userId, userId, workspaceId, folderId, size};
        List<Map<String, Object>> items = jdbc.query(sql, (rs, row) -> mapRow(rs), args);
        return Map.of("items", items, "nextCursor", "");
    }

    @Transactional
    public Map<String, Object> rename(UUID userId, UUID documentId, String title, String ifMatch) {
        access.require(userId, documentId, DocumentRole.EDITOR);
        String name = checkTitle(title);
        String sql = ifMatch == null || ifMatch.isBlank()
                ? "UPDATE documents SET title = ?, version = version + 1, updated_at = now() WHERE id = ? AND status = 'active'"
                : "UPDATE documents SET title = ?, version = version + 1, updated_at = now() WHERE id = ? AND status = 'active' AND version = ?";
        int updated = ifMatch == null || ifMatch.isBlank()
                ? jdbc.update(sql, name, documentId)
                : jdbc.update(sql, name, documentId, Integer.parseInt(ifMatch));
        if (updated == 0) {
            throw new ApiException(409, "VERSION_CONFLICT", "The document changed. Reload and try again.");
        }
        return get(userId, documentId);
    }

    @Transactional
    public Map<String, Object> move(UUID userId, UUID documentId, UUID folderId) {
        access.require(userId, documentId, DocumentRole.EDITOR);
        UUID workspaceId = jdbc.queryForObject("SELECT workspace_id FROM documents WHERE id = ?", UUID.class, documentId);
        if (folderId != null) {
            requireFolder(workspaceId, folderId);
        }
        jdbc.update("UPDATE documents SET folder_id = ?, updated_at = now() WHERE id = ?", folderId, documentId);
        return get(userId, documentId);
    }

    @Transactional
    public Map<String, Object> duplicate(UUID userId, UUID documentId, UUID workspaceId, UUID folderId, String title) {
        access.require(userId, documentId, DocumentRole.VIEWER);
        access.requireWorkspaceMember(userId, workspaceId);
        if (folderId != null) {
            requireFolder(workspaceId, folderId);
        }
        byte[] state = latestState(documentId);
        UUID id = Ids.uuidV7();
        String name = title == null || title.isBlank() ? "Copy of document" : checkTitle(title);
        jdbc.update("INSERT INTO documents (id, workspace_id, folder_id, owner_id, title) VALUES (?, ?, ?, ?, ?)",
                id, workspaceId, folderId, userId, name);
        jdbc.update("INSERT INTO document_epochs (document_id, epoch) VALUES (?, 1)", id);
        jdbc.update(
                """
                INSERT INTO document_snapshots
                  (id, document_id, epoch, through_seq, schema_version, kind, byte_size, state, created_by)
                VALUES (?, ?, 1, 0, 1, 'base', ?, ?, ?)
                """,
                Ids.uuidV7(), id, state.length, state, userId);
        jdbc.update(
                "INSERT INTO document_permissions (id, document_id, principal_type, user_id, role, granted_by) VALUES (?, ?, 'user', ?, 'owner', ?)",
                Ids.uuidV7(), id, userId, userId);
        return get(userId, id);
    }

    @Transactional
    public Map<String, Object> trash(UUID userId, UUID documentId) {
        access.require(userId, documentId, DocumentRole.EDITOR);
        jdbc.update(
                """
                UPDATE documents SET status = 'trashed', trashed_at = now(), purge_after = now() + interval '30 days', updated_at = now()
                WHERE id = ? AND status = 'active'
                """,
                documentId);
        return Map.of("status", "trashed", "purgeAfter", Instant.now().plus(java.time.Duration.ofDays(30)).toString());
    }

    @Transactional
    public Map<String, Object> restore(UUID userId, UUID documentId) {
        access.require(userId, documentId, DocumentRole.EDITOR);
        int updated = jdbc.update(
                """
                UPDATE documents SET status = 'active', trashed_at = NULL, purge_after = NULL, trash_batch_id = NULL, updated_at = now()
                WHERE id = ? AND status = 'trashed' AND (purge_after IS NULL OR purge_after > now())
                """,
                documentId);
        if (updated == 0) {
            throw new ApiException(409, "CONFLICT", "This document cannot be restored.");
        }
        return get(userId, documentId);
    }

    @Transactional
    public void purgeNow(UUID userId, UUID documentId) {
        access.require(userId, documentId, DocumentRole.OWNER);
        String status = jdbc.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, documentId);
        if (!"trashed".equals(status)) {
            throw new ApiException(409, "CONFLICT", "Trash the document before purging it.");
        }
        purge.purge(documentId);
    }

    public void star(UUID userId, UUID documentId, boolean on) {
        access.require(userId, documentId, DocumentRole.VIEWER);
        if (on) {
            jdbc.update("INSERT INTO document_stars (user_id, document_id) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, documentId);
        } else {
            jdbc.update("DELETE FROM document_stars WHERE user_id = ? AND document_id = ?", userId, documentId);
        }
    }

    public Map<String, Object> recent(UUID userId, UUID workspaceId) {
        access.requireWorkspaceMember(userId, workspaceId);
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT d.*, p.role, EXISTS(SELECT 1 FROM document_stars s WHERE s.document_id = d.id AND s.user_id = ?) AS starred
                FROM recent_documents r
                JOIN documents d ON d.id = r.document_id
                JOIN document_permissions p ON p.document_id = d.id AND p.user_id = ? AND p.revoked_at IS NULL
                WHERE r.user_id = ? AND d.workspace_id = ? AND d.status = 'active'
                ORDER BY r.opened_at DESC LIMIT 50
                """,
                (rs, row) -> mapRow(rs), userId, userId, userId, workspaceId);
        return Map.of("items", items, "nextCursor", "");
    }

    public Map<String, Object> starred(UUID userId, UUID workspaceId) {
        access.requireWorkspaceMember(userId, workspaceId);
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT d.*, p.role, true AS starred
                FROM document_stars s
                JOIN documents d ON d.id = s.document_id
                JOIN document_permissions p ON p.document_id = d.id AND p.user_id = ? AND p.revoked_at IS NULL
                WHERE s.user_id = ? AND d.workspace_id = ? AND d.status = 'active'
                ORDER BY s.created_at DESC LIMIT 100
                """,
                (rs, row) -> mapRow(rs), userId, userId, workspaceId);
        return Map.of("items", items, "nextCursor", "");
    }

    public Map<String, Object> trashList(UUID userId, UUID workspaceId) {
        access.requireWorkspaceMember(userId, workspaceId);
        List<Map<String, Object>> documents = jdbc.query(
                """
                SELECT d.*, p.role, false AS starred FROM documents d
                JOIN document_permissions p ON p.document_id = d.id AND p.user_id = ? AND p.revoked_at IS NULL
                WHERE d.workspace_id = ? AND d.status = 'trashed'
                ORDER BY d.trashed_at DESC
                """,
                (rs, row) -> mapRow(rs), userId, userId, workspaceId);
        return Map.of("documents", documents);
    }

    public Map<String, Object> search(UUID userId, UUID workspaceId, String query) {
        access.requireWorkspaceMember(userId, workspaceId);
        if (query == null || query.isBlank() || query.length() > 100) {
            throw new ApiException(400, "VALIDATION_FAILED", "Enter a search query.");
        }
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT d.*, p.role, false AS starred FROM documents d
                JOIN document_permissions p ON p.document_id = d.id AND p.user_id = ? AND p.revoked_at IS NULL
                WHERE d.workspace_id = ? AND d.status = 'active' AND d.title ILIKE ? ESCAPE '\\'
                ORDER BY d.updated_at DESC LIMIT 50
                """,
                (rs, row) -> mapRow(rs), userId, workspaceId, "%" + escaped + "%");
        return Map.of("items", items, "nextCursor", "");
    }

    public ContentUpdateService.Ack append(UUID userId, UUID documentId, UUID clientId, UUID updateId, int epoch,
                                           String intent, String payloadBase64) {
        DocumentRole minimum = "suggest".equals(intent) ? DocumentRole.COMMENTER : DocumentRole.EDITOR;
        access.require(userId, documentId, minimum);
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(payloadBase64);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "VALIDATION_FAILED", "Update payload is not valid base64.");
        }
        return updates.append(new ContentUpdateService.AppendCommand(
                documentId, userId, clientId, updateId, epoch, intent == null ? "edit" : intent, payload));
    }

    @Transactional
    public Map<String, Object> contentState(UUID userId, UUID documentId) {
        access.require(userId, documentId, DocumentRole.VIEWER);
        var meta = jdbc.queryForMap("SELECT epoch, schema_version FROM documents WHERE id = ?", documentId);
        int epoch = ((Number) meta.get("epoch")).intValue();
        var snapshots = jdbc.query(
                """
                SELECT through_seq, state FROM document_snapshots
                WHERE document_id = ? AND epoch = ? AND state IS NOT NULL
                ORDER BY through_seq DESC LIMIT 1
                """,
                (rs, row) -> new Snapshot(rs.getLong("through_seq"), rs.getBytes("state")), documentId, epoch);
        long through = snapshots.isEmpty() ? 0 : snapshots.get(0).through();
        byte[] state = snapshots.isEmpty() ? emptySnapshot : snapshots.get(0).state();
        List<Map<String, Object>> tail = jdbc.query(
                "SELECT seq, payload FROM collab_updates WHERE document_id = ? AND epoch = ? AND seq > ? ORDER BY seq",
                (rs, row) -> Map.of("seq", rs.getLong("seq"), "payloadBase64", Base64.getEncoder().encodeToString(rs.getBytes("payload"))),
                documentId, epoch, through);
        jdbc.update(
                """
                INSERT INTO recent_documents (user_id, document_id, opened_at) VALUES (?, ?, now())
                ON CONFLICT (user_id, document_id) DO UPDATE SET opened_at = now()
                """,
                userId, documentId);
        jdbc.update(
                """
                DELETE FROM recent_documents WHERE user_id = ? AND document_id NOT IN (
                  SELECT document_id FROM recent_documents WHERE user_id = ? ORDER BY opened_at DESC LIMIT 50
                )
                """,
                userId, userId);
        return Map.of(
                "epoch", epoch,
                "schemaVersion", ((Number) meta.get("schema_version")).intValue(),
                "snapshotBase64", Base64.getEncoder().encodeToString(state),
                "throughSeq", through,
                "updates", tail);
    }

    public List<Map<String, Object>> workspaces(UUID userId) {
        return jdbc.query(
                """
                SELECT w.id, w.name, m.role, w.link_sharing FROM workspaces w
                JOIN workspace_memberships m ON m.workspace_id = w.id
                WHERE m.user_id = ? ORDER BY w.created_at
                """,
                (rs, row) -> Map.of("id", rs.getObject("id", UUID.class), "name", rs.getString("name"),
                        "role", rs.getString("role"), "linkSharing", rs.getString("link_sharing")),
                userId);
    }

    private byte[] latestState(UUID documentId) {
        List<byte[]> states = jdbc.query(
                """
                SELECT state FROM document_snapshots
                WHERE document_id = ? AND state IS NOT NULL
                ORDER BY epoch DESC, through_seq DESC LIMIT 1
                """,
                (rs, row) -> rs.getBytes(1), documentId);
        return states.isEmpty() ? emptySnapshot : states.get(0);
    }

    private void requireFolder(UUID workspaceId, UUID folderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM folders WHERE id = ? AND workspace_id = ? AND status = 'active'",
                Integer.class, folderId, workspaceId);
        if (count == null || count == 0) {
            throw new ApiException(404, "NOT_FOUND", "Folder is not available.");
        }
    }

    private Map<String, Object> one(UUID userId, UUID documentId, DocumentRole role) {
        return jdbc.queryForObject(
                """
                SELECT d.*, EXISTS(SELECT 1 FROM document_stars s WHERE s.document_id = d.id AND s.user_id = ?) AS starred,
                       'viewer' AS role
                FROM documents d WHERE d.id = ?
                """,
                (rs, row) -> {
                    Map<String, Object> mapped = new LinkedHashMap<>(mapRow(rs));
                    mapped.put("effectiveRole", role.name().toLowerCase());
                    return mapped;
                }, userId, documentId);
    }

    private static Map<String, Object> mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", rs.getObject("id", UUID.class));
        body.put("workspaceId", rs.getObject("workspace_id", UUID.class));
        body.put("folderId", rs.getObject("folder_id", UUID.class));
        body.put("ownerId", rs.getObject("owner_id", UUID.class));
        body.put("title", rs.getString("title"));
        body.put("status", rs.getString("status"));
        body.put("epoch", rs.getInt("epoch"));
        body.put("schemaVersion", rs.getInt("schema_version"));
        body.put("wordCount", rs.getInt("word_count"));
        body.put("effectiveRole", rs.getString("role") == null ? "owner" : rs.getString("role"));
        body.put("starred", rs.getBoolean("starred"));
        body.put("version", rs.getInt("version"));
        body.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        body.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
        Timestamp edited = rs.getTimestamp("last_edited_at");
        body.put("lastEditedAt", edited == null ? "" : edited.toInstant().toString());
        return body;
    }

    static String checkTitle(String title) {
        if (title == null || title.trim().isEmpty() || title.trim().length() > 200) {
            throw new ApiException(400, "VALIDATION_FAILED", "Title is required.",
                    List.of(new ApiException.FieldDetail("title", "blank")));
        }
        return title.trim();
    }

    private record Snapshot(long through, byte[] state) {}
}
