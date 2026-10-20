package com.collabdocs.persistence;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.DocumentRole;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DocumentAccessService {
    private final JdbcTemplate jdbc;
    private final boolean adminImplicitAccess;

    public DocumentAccessService(JdbcTemplate jdbc,
                                 @Value("${collabdocs.admin-implicit-access:true}") boolean adminImplicitAccess) {
        this.jdbc = jdbc;
        this.adminImplicitAccess = adminImplicitAccess;
    }

    public DocumentRole require(UUID userId, UUID documentId, DocumentRole minimum) {
        DocumentRole role = resolve(userId, documentId);
        if (!role.atLeast(minimum)) {
            throw new ApiException(403, "FORBIDDEN", "You do not have permission for this document.");
        }
        return role;
    }

    public DocumentRole resolve(UUID userId, UUID documentId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM documents WHERE id = ? AND status <> 'purged')",
                Boolean.class, documentId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(404, "NOT_FOUND", "Document is not available.");
        }
        var active = jdbc.query(
                """
                SELECT role FROM document_permissions
                WHERE document_id = ? AND user_id = ? AND principal_type = 'user'
                  AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())
                """,
                (rs, row) -> DocumentRole.parse(rs.getString("role")), documentId, userId);
        DocumentRole direct = active.stream().max(java.util.Comparator.comparingInt(Enum::ordinal)).orElse(null);
        if (direct == null && adminImplicitAccess && isWorkspaceAdmin(userId, documentId)) {
            direct = DocumentRole.EDITOR;
        }
        if (direct != null) {
            return direct;
        }
        Boolean ever = jdbc.queryForObject(
                """
                SELECT EXISTS(
                  SELECT 1 FROM document_permissions
                  WHERE document_id = ? AND user_id = ? AND revoked_at IS NOT NULL
                )
                """,
                Boolean.class, documentId, userId);
        if (Boolean.TRUE.equals(ever)) {
            throw new ApiException(403, "FORBIDDEN", "Access to this document was revoked.");
        }
        throw new ApiException(404, "NOT_FOUND", "Document is not available.");
    }

    public void requireWorkspaceMember(UUID userId, UUID workspaceId) {
        Boolean member = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM workspace_memberships WHERE workspace_id = ? AND user_id = ?)",
                Boolean.class, workspaceId, userId);
        if (!Boolean.TRUE.equals(member)) {
            throw new ApiException(404, "NOT_FOUND", "Workspace is not available.");
        }
    }

    private boolean isWorkspaceAdmin(UUID userId, UUID documentId) {
        Boolean admin = jdbc.queryForObject(
                """
                SELECT EXISTS(
                  SELECT 1 FROM documents d
                  JOIN workspace_memberships m ON m.workspace_id = d.workspace_id
                  WHERE d.id = ? AND m.user_id = ? AND m.role IN ('owner', 'admin')
                )
                """,
                Boolean.class, documentId, userId);
        return Boolean.TRUE.equals(admin);
    }
}
