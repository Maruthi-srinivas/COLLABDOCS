package com.collabdocs.api.docs;

import com.collabdocs.api.auth.MailPort;
import com.collabdocs.common.ApiException;
import com.collabdocs.common.DocumentRole;
import com.collabdocs.common.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class ShareService {
    private final JdbcTemplate jdbc;
    private final MailPort mail;
    private final AclEvents acl;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final String publicUrl;

    public ShareService(JdbcTemplate jdbc, MailPort mail, AclEvents acl, NotificationService notifications, ObjectMapper mapper,
                        @Value("${collabdocs.app-public-url}") String publicUrl) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.acl = acl;
        this.notifications = notifications;
        this.mapper = mapper;
        this.publicUrl = publicUrl;
    }

    public List<Map<String, Object>> list(UUID actor, UUID documentId) {
        requireManager(actor, documentId);
        return jdbc.query(
                """
                SELECT p.id, p.principal_type, p.user_id, COALESCE(u.email, p.email, '') AS email, p.role, p.created_at
                FROM document_permissions p
                LEFT JOIN users u ON u.id = p.user_id
                WHERE p.document_id = ? AND p.revoked_at IS NULL ORDER BY p.created_at
                """,
                (rs, row) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", rs.getObject("id", UUID.class));
                    body.put("principalType", rs.getString("principal_type"));
                    body.put("userId", rs.getObject("user_id", UUID.class));
                    body.put("email", rs.getString("email"));
                    body.put("role", rs.getString("role"));
                    body.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return body;
                }, documentId);
    }

    @Transactional
    public Map<String, Object> grant(UUID actor, UUID documentId, UUID userId, String email, String role) {
        requireManager(actor, documentId);
        DocumentRole parsed = shareRole(role);
        if (userId != null && userId.equals(actor)) {
            throw new ApiException(422, "SELF_SHARE", "You already have access.");
        }
        UUID workspaceId = workspace(documentId);
        if (userId != null) {
            List<String> known = jdbc.query("SELECT email FROM users WHERE id = ?", (rs, row) -> rs.getString(1), userId);
            if (known.isEmpty()) throw new ApiException(404, "NOT_FOUND", "User is not available.");
            Map<String, Object> created = insertUserGrant(actor, documentId, userId, parsed);
            audit(workspaceId, actor, "grant", documentId, Map.of("userId", userId.toString(), "role", parsed.name().toLowerCase()));
            afterCommit(() -> acl.publish(documentId, userId, "role", parsed.name().toLowerCase()));
            mail.send(known.get(0), "A document was shared with you", publicUrl + "/d/" + documentId);
            notifications.notify(userId, "share_invite", Map.of("documentId", documentId.toString()));
            return created;
        }
        if (email == null || !email.contains("@")) {
            throw new ApiException(400, "VALIDATION_FAILED", "Choose a person or an email.");
        }
        String normalized = email.trim().toLowerCase();
        String actorEmail = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, actor);
        if (normalized.equals(actorEmail)) throw new ApiException(422, "SELF_SHARE", "You already have access.");
        List<UUID> existing = jdbc.query("SELECT id FROM users WHERE email = ?",
                (rs, row) -> rs.getObject(1, UUID.class), normalized);
        if (!existing.isEmpty()) {
            Map<String, Object> created = insertUserGrant(actor, documentId, existing.get(0), parsed);
            audit(workspaceId, actor, "grant", documentId, Map.of("userId", existing.get(0).toString(), "role", parsed.name().toLowerCase()));
            UUID granted = existing.get(0);
            afterCommit(() -> acl.publish(documentId, granted, "role", parsed.name().toLowerCase()));
            mail.send(normalized, "A document was shared with you", publicUrl + "/d/" + documentId);
            notifications.notify(existing.get(0), "share_invite", Map.of("documentId", documentId.toString()));
            return created;
        }
        UUID id = Ids.uuidV7();
        jdbc.update(
                """
                INSERT INTO document_permissions (id, document_id, principal_type, email, role, granted_by)
                VALUES (?, ?, 'email_invite', ?, ?, ?)
                """,
                id, documentId, normalized, parsed.name().toLowerCase(), actor);
        audit(workspaceId, actor, "invite", documentId, Map.of("email", normalized, "role", parsed.name().toLowerCase()));
        mail.send(normalized, "You are invited to a CollabDocs document",
                "Create an account with this email, then open " + publicUrl + "/d/" + documentId);
        return Map.of("id", id, "principalType", "email_invite", "email", normalized, "role", parsed.name().toLowerCase());
    }

    @Transactional
    public Map<String, Object> changeRole(UUID actor, UUID documentId, UUID permissionId, String role) {
        requireManager(actor, documentId);
        DocumentRole parsed = shareRole(role);
        var rows = jdbc.query("SELECT user_id, role FROM document_permissions WHERE id = ? AND document_id = ? AND revoked_at IS NULL",
                (rs, row) -> Map.of("userId", rs.getObject("user_id", UUID.class) == null ? "" : rs.getObject("user_id", UUID.class).toString(),
                        "role", rs.getString("role")), permissionId, documentId);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Grant is not available.");
        if ("owner".equals(rows.get(0).get("role"))) {
            throw new ApiException(409, "OWNER_ROLE", "Transfer ownership instead of changing the owner role.");
        }
        jdbc.update("UPDATE document_permissions SET role = ? WHERE id = ?", parsed.name().toLowerCase(), permissionId);
        audit(workspace(documentId), actor, "role_change", documentId, Map.of("permissionId", permissionId.toString(), "role", parsed.name().toLowerCase()));
        String user = rows.get(0).get("userId").toString();
        if (!user.isBlank()) {
            afterCommit(() -> acl.publish(documentId, UUID.fromString(user), "role", parsed.name().toLowerCase()));
        }
        return Map.of("id", permissionId, "role", parsed.name().toLowerCase());
    }

    @Transactional
    public void revoke(UUID actor, UUID documentId, UUID permissionId) {
        requireManager(actor, documentId);
        var rows = jdbc.query("SELECT user_id, role FROM document_permissions WHERE id = ? AND document_id = ? AND revoked_at IS NULL",
                (rs, row) -> Map.of("userId", rs.getObject("user_id", UUID.class) == null ? "" : rs.getObject("user_id", UUID.class).toString(),
                        "role", rs.getString("role")), permissionId, documentId);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Grant is not available.");
        if ("owner".equals(rows.get(0).get("role"))) {
            throw new ApiException(409, "OWNER_ROLE", "The owner grant cannot be revoked.");
        }
        jdbc.update("UPDATE document_permissions SET revoked_at = now() WHERE id = ?", permissionId);
        audit(workspace(documentId), actor, "revoke", documentId, Map.of("permissionId", permissionId.toString()));
        String user = rows.get(0).get("userId").toString();
        if (!user.isBlank()) afterCommit(() -> acl.publish(documentId, UUID.fromString(user), "revoked", null));
    }

    @Transactional
    public Map<String, Object> transfer(UUID actor, UUID documentId, UUID nextOwner) {
        UUID ownerId = jdbc.queryForObject("SELECT owner_id FROM documents WHERE id = ?", UUID.class, documentId);
        if (ownerId == null || !ownerId.equals(actor)) {
            throw new ApiException(403, "FORBIDDEN", "Only the owner can transfer this document.");
        }
        if (nextOwner.equals(actor)) throw new ApiException(422, "SELF_SHARE", "You already own this document.");
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, nextOwner);
        if (found == null || found == 0) throw new ApiException(404, "NOT_FOUND", "User is not available.");
        jdbc.update("UPDATE documents SET owner_id = ?, updated_at = now() WHERE id = ?", nextOwner, documentId);
        jdbc.update("UPDATE document_permissions SET role = 'editor' WHERE document_id = ? AND user_id = ? AND revoked_at IS NULL AND role = 'owner'",
                documentId, actor);
        int updated = jdbc.update("UPDATE document_permissions SET role = 'owner' WHERE document_id = ? AND user_id = ? AND revoked_at IS NULL",
                documentId, nextOwner);
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO document_permissions (id, document_id, principal_type, user_id, role, granted_by)
                    VALUES (?, ?, 'user', ?, 'owner', ?)
                    """,
                    Ids.uuidV7(), documentId, nextOwner, actor);
        }
        audit(workspace(documentId), actor, "transfer", documentId, Map.of("userId", nextOwner.toString()));
        afterCommit(() -> {
            acl.publish(documentId, actor, "role", "editor");
            acl.publish(documentId, nextOwner, "role", "owner");
        });
        return Map.of("id", documentId, "ownerId", nextOwner);
    }

    public List<Map<String, Object>> links(UUID actor, UUID documentId) {
        requireManager(actor, documentId);
        return jdbc.query(
                """
                SELECT id, role, expires_at, revoked_at, created_at FROM share_links
                WHERE document_id = ? ORDER BY created_at DESC
                """,
                (rs, row) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", rs.getObject("id", UUID.class));
                    body.put("role", rs.getString("role"));
                    body.put("expiresAt", rs.getTimestamp("expires_at") == null ? "" : rs.getTimestamp("expires_at").toInstant().toString());
                    body.put("revoked", rs.getTimestamp("revoked_at") != null);
                    body.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return body;
                }, documentId);
    }

    @Transactional
    public Map<String, Object> createLink(UUID actor, UUID documentId, String role, String expiresAt) {
        requireManager(actor, documentId);
        DocumentRole parsed = shareRole(role);
        String token = Ids.randomToken();
        UUID id = Ids.uuidV7();
        jdbc.update(
                """
                INSERT INTO share_links (id, document_id, token_hash, role, expires_at, created_by)
                VALUES (?, ?, ?, ?, ?::timestamptz, ?)
                """,
                id, documentId, Ids.sha256(token), parsed.name().toLowerCase(), blank(expiresAt), actor);
        audit(workspace(documentId), actor, "link_create", documentId, Map.of("linkId", id.toString(), "role", parsed.name().toLowerCase()));
        return Map.of("id", id, "token", token, "role", parsed.name().toLowerCase(), "url", publicUrl + "/share/" + token);
    }

    @Transactional
    public void revokeLink(UUID actor, UUID linkId) {
        UUID documentId = jdbc.queryForObject("SELECT document_id FROM share_links WHERE id = ?", UUID.class, linkId);
        if (documentId == null) throw new ApiException(404, "NOT_FOUND", "Link is not available.");
        requireManager(actor, documentId);
        jdbc.update("UPDATE share_links SET revoked_at = now() WHERE id = ?", linkId);
        audit(workspace(documentId), actor, "link_revoke", documentId, Map.of("linkId", linkId.toString()));
    }

    @Transactional
    public Map<String, Object> redeem(UUID actor, String token) {
        var rows = jdbc.query(
                """
                SELECT id, document_id, role FROM share_links
                WHERE token_hash = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())
                """,
                (rs, row) -> Map.of("id", rs.getObject("id", UUID.class), "documentId", rs.getObject("document_id", UUID.class),
                        "role", rs.getString("role")), Ids.sha256(token));
        if (rows.isEmpty()) throw new ApiException(410, "LINK_EXPIRED", "This link is no longer available.");
        UUID documentId = (UUID) rows.get(0).get("documentId");
        DocumentRole role = DocumentRole.parse(rows.get(0).get("role").toString());
        Integer already = jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_permissions WHERE document_id = ? AND user_id = ? AND revoked_at IS NULL",
                Integer.class, documentId, actor);
        if (already == null || already == 0) {
            jdbc.update(
                    """
                    INSERT INTO document_permissions (id, document_id, principal_type, user_id, role, granted_by)
                    VALUES (?, ?, 'user', ?, ?, ?)
                    """,
                    Ids.uuidV7(), documentId, actor, role.name().toLowerCase(), actor);
            audit(workspace(documentId), actor, "link_redeem", documentId, Map.of("role", role.name().toLowerCase()));
        }
        return Map.of("documentId", documentId, "role", role.name().toLowerCase());
    }

    @Transactional
    public Map<String, Object> requestAccess(UUID actor, UUID documentId) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ? AND status <> 'purged'", Integer.class, documentId);
        if (exists == null || exists == 0) throw new ApiException(404, "NOT_FOUND", "Document is not available.");
        var open = jdbc.query("SELECT id FROM access_requests WHERE document_id = ? AND requester_id = ? AND status = 'open'",
                (rs, row) -> rs.getObject(1, UUID.class), documentId, actor);
        if (!open.isEmpty()) return Map.of("id", open.get(0), "status", "open", "existing", true);
        UUID id = Ids.uuidV7();
        jdbc.update("INSERT INTO access_requests (id, document_id, requester_id, status) VALUES (?, ?, ?, 'open')", id, documentId, actor);
        UUID owner = jdbc.queryForObject("SELECT owner_id FROM documents WHERE id = ?", UUID.class, documentId);
        if (owner != null && !owner.equals(actor)) {
            notifications.notify(owner, "access_request", Map.of("documentId", documentId.toString(), "requestId", id.toString()));
        }
        return Map.of("id", id, "status", "open", "existing", false);
    }

    @Transactional
    public Map<String, Object> decide(UUID actor, UUID requestId, boolean approve, String role) {
        var rows = jdbc.query("SELECT document_id, requester_id, status FROM access_requests WHERE id = ?",
                (rs, row) -> Map.of("documentId", rs.getObject("document_id", UUID.class), "requesterId", rs.getObject("requester_id", UUID.class),
                        "status", rs.getString("status")), requestId);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Request is not available.");
        UUID documentId = (UUID) rows.get(0).get("documentId");
        UUID requester = (UUID) rows.get(0).get("requesterId");
        requireManager(actor, documentId);
        if (!"open".equals(rows.get(0).get("status"))) throw new ApiException(409, "CONFLICT", "This request is already decided.");
        if (!approve) {
            jdbc.update("UPDATE access_requests SET status = 'denied', decided_by = ?, decided_at = now() WHERE id = ?", actor, requestId);
            audit(workspace(documentId), actor, "access_deny", documentId, Map.of("requestId", requestId.toString()));
            return Map.of("id", requestId, "status", "denied");
        }
        DocumentRole parsed = shareRole(role);
        insertUserGrant(actor, documentId, requester, parsed);
        jdbc.update("UPDATE access_requests SET status = 'approved', decided_by = ?, decided_at = now() WHERE id = ?", actor, requestId);
        audit(workspace(documentId), actor, "access_approve", documentId, Map.of("requestId", requestId.toString(), "role", parsed.name().toLowerCase()));
        afterCommit(() -> acl.publish(documentId, requester, "role", parsed.name().toLowerCase()));
        return Map.of("id", requestId, "status", "approved", "role", parsed.name().toLowerCase());
    }

    private Map<String, Object> insertUserGrant(UUID actor, UUID documentId, UUID userId, DocumentRole role) {
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_permissions WHERE document_id = ? AND user_id = ? AND revoked_at IS NULL",
                Integer.class, documentId, userId);
        if (active != null && active > 0) throw new ApiException(409, "CONFLICT", "That person already has access.");
        UUID id = Ids.uuidV7();
        jdbc.update(
                """
                INSERT INTO document_permissions (id, document_id, principal_type, user_id, role, granted_by)
                VALUES (?, ?, 'user', ?, ?, ?)
                """,
                id, documentId, userId, role.name().toLowerCase(), actor);
        return Map.of("id", id, "principalType", "user", "userId", userId, "role", role.name().toLowerCase());
    }

    private void requireManager(UUID actor, UUID documentId) {
        Boolean allowed = jdbc.queryForObject(
                """
                SELECT EXISTS(
                  SELECT 1 FROM documents d
                  LEFT JOIN workspace_memberships m ON m.workspace_id = d.workspace_id AND m.user_id = ?
                  WHERE d.id = ? AND (d.owner_id = ? OR m.role IN ('owner', 'admin'))
                )
                """,
                Boolean.class, actor, documentId, actor);
        if (!Boolean.TRUE.equals(allowed)) throw new ApiException(403, "FORBIDDEN", "You cannot share this document.");
    }

    private UUID workspace(UUID documentId) {
        return jdbc.queryForObject("SELECT workspace_id FROM documents WHERE id = ?", UUID.class, documentId);
    }

    private void audit(UUID workspaceId, UUID actor, String action, UUID documentId, Map<String, String> metadata) {
        try {
            jdbc.update(
                    """
                    INSERT INTO audit_events (workspace_id, actor_id, action, target_type, target_id, metadata)
                    VALUES (?, ?, ?, 'document', ?, ?::jsonb)
                    """,
                    workspaceId, actor, action, documentId, mapper.writeValueAsString(metadata));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static DocumentRole shareRole(String role) {
        try {
            DocumentRole parsed = DocumentRole.parse(role);
            if (parsed == DocumentRole.OWNER) throw new ApiException(422, "UNPROCESSABLE", "Grant viewer, commenter, or editor.");
            return parsed;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(400, "VALIDATION_FAILED", "Role is invalid.");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
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
