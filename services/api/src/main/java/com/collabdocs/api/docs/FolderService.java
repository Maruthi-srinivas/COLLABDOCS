package com.collabdocs.api.docs;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.Ids;
import com.collabdocs.persistence.DocumentAccessService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FolderService {
    private final JdbcTemplate jdbc;
    private final DocumentAccessService access;

    public FolderService(JdbcTemplate jdbc, DocumentAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public List<Map<String, Object>> list(UUID userId, UUID workspaceId, UUID parentId) {
        access.requireWorkspaceMember(userId, workspaceId);
        String clause = parentId == null ? "parent_id IS NULL" : "parent_id = ?";
        String sql = "SELECT id, name, parent_id FROM folders WHERE workspace_id = ? AND status = 'active' AND " + clause + " ORDER BY name";
        return parentId == null
                ? jdbc.query(sql, (rs, row) -> map(rs), workspaceId)
                : jdbc.query(sql, (rs, row) -> map(rs), workspaceId, parentId);
    }

    @Transactional
    public Map<String, Object> create(UUID userId, UUID workspaceId, String name, UUID parentId) {
        access.requireWorkspaceMember(userId, workspaceId);
        String folderName = checkName(name);
        if (parentId != null) {
            assertDepth(parentId, null);
        }
        UUID id = Ids.uuidV7();
        jdbc.update("INSERT INTO folders (id, workspace_id, parent_id, name, owner_id) VALUES (?, ?, ?, ?, ?)",
                id, workspaceId, parentId, folderName, userId);
        return Map.of("id", id, "name", folderName, "parentId", parentId == null ? "" : parentId);
    }

    @Transactional
    public Map<String, Object> update(UUID userId, UUID folderId, String name, UUID parentId, boolean parentPresent) {
        UUID workspaceId = workspaceOf(folderId);
        access.requireWorkspaceMember(userId, workspaceId);
        if (name != null) {
            jdbc.update("UPDATE folders SET name = ?, updated_at = now() WHERE id = ?", checkName(name), folderId);
        }
        if (parentPresent) {
            if (folderId.equals(parentId)) {
                throw new ApiException(422, "UNPROCESSABLE", "A folder cannot contain itself.");
            }
            if (parentId != null) {
                assertDepth(parentId, folderId);
            }
            jdbc.update("UPDATE folders SET parent_id = ?, updated_at = now() WHERE id = ?", parentId, folderId);
        }
        return jdbc.queryForObject("SELECT id, name, parent_id FROM folders WHERE id = ?", (rs, row) -> map(rs), folderId);
    }

    @Transactional
    public Map<String, Object> trash(UUID userId, UUID folderId) {
        UUID workspaceId = workspaceOf(folderId);
        access.requireWorkspaceMember(userId, workspaceId);
        UUID batch = Ids.uuidV7();
        jdbc.update(
                """
                WITH RECURSIVE tree AS (
                  SELECT id FROM folders WHERE id = ?
                  UNION ALL
                  SELECT f.id FROM folders f JOIN tree t ON f.parent_id = t.id
                )
                UPDATE folders SET status = 'trashed', trash_batch_id = ?, trashed_at = now(), updated_at = now()
                WHERE id IN (SELECT id FROM tree)
                """,
                folderId, batch);
        jdbc.update(
                """
                UPDATE documents SET status = 'trashed', trash_batch_id = ?, trashed_at = now(),
                  purge_after = now() + interval '30 days', updated_at = now()
                WHERE status = 'active' AND folder_id IN (SELECT id FROM folders WHERE trash_batch_id = ?)
                """,
                batch, batch);
        return Map.of("trashBatchId", batch);
    }

    @Transactional
    public void restore(UUID userId, UUID folderId) {
        UUID workspaceId = workspaceOf(folderId);
        access.requireWorkspaceMember(userId, workspaceId);
        UUID batch = jdbc.queryForObject("SELECT trash_batch_id FROM folders WHERE id = ?", UUID.class, folderId);
        if (batch == null) {
            throw new ApiException(409, "CONFLICT", "This folder is not in the trash.");
        }
        jdbc.update("UPDATE folders SET status = 'active', trashed_at = NULL, trash_batch_id = NULL, updated_at = now() WHERE trash_batch_id = ?", batch);
        jdbc.update(
                """
                UPDATE documents SET status = 'active', trashed_at = NULL, purge_after = NULL, trash_batch_id = NULL, updated_at = now()
                WHERE trash_batch_id = ?
                """,
                batch);
    }

    private void assertDepth(UUID parentId, UUID movingId) {
        Integer depth = jdbc.queryForObject(
                """
                WITH RECURSIVE ancestors AS (
                  SELECT id, parent_id, 1 AS depth FROM folders WHERE id = ?
                  UNION ALL
                  SELECT f.id, f.parent_id, a.depth + 1 FROM folders f JOIN ancestors a ON f.id = a.parent_id
                )
                SELECT COALESCE(MAX(depth), 0) FROM ancestors
                """,
                Integer.class, parentId);
        if (depth != null && depth >= 20) {
            throw new ApiException(422, "FOLDER_DEPTH", "Folders can only be nested 20 levels deep.");
        }
        if (movingId != null) {
            Boolean cycle = jdbc.queryForObject(
                    """
                    WITH RECURSIVE ancestors AS (
                      SELECT id, parent_id FROM folders WHERE id = ?
                      UNION ALL
                      SELECT f.id, f.parent_id FROM folders f JOIN ancestors a ON f.id = a.parent_id
                    )
                    SELECT EXISTS(SELECT 1 FROM ancestors WHERE id = ?)
                    """,
                    Boolean.class, parentId, movingId);
            if (Boolean.TRUE.equals(cycle)) {
                throw new ApiException(422, "UNPROCESSABLE", "A folder cannot be moved into itself.");
            }
        }
    }

    private UUID workspaceOf(UUID folderId) {
        List<UUID> ids = jdbc.query("SELECT workspace_id FROM folders WHERE id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), folderId);
        if (ids.isEmpty()) {
            throw new ApiException(404, "NOT_FOUND", "Folder is not available.");
        }
        return ids.get(0);
    }

    private static Map<String, Object> map(java.sql.ResultSet rs) throws java.sql.SQLException {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("id", rs.getObject("id", UUID.class));
        body.put("name", rs.getString("name"));
        body.put("parentId", rs.getObject("parent_id", UUID.class));
        return body;
    }

    private static String checkName(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 120) {
            throw new ApiException(400, "VALIDATION_FAILED", "Folder name is required.");
        }
        return name.trim();
    }
}
