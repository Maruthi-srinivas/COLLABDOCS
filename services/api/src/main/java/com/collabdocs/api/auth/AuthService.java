package com.collabdocs.api.auth;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.Ids;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final PasswordPolicy passwords;
    private final JwtService jwt;
    private final MailPort mail;
    private final RateLimiter rates;
    private final SessionDenylist denylist;
    private final PlatformTransactionManager transactions;
    private final boolean secureCookie;
    private final String publicUrl;

    public AuthService(JdbcTemplate jdbc, PasswordPolicy passwords, JwtService jwt, MailPort mail, RateLimiter rates,
                       SessionDenylist denylist, PlatformTransactionManager transactions,
                       @Value("${collabdocs.cookie.secure}") boolean secureCookie,
                       @Value("${collabdocs.app-public-url}") String publicUrl) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.jwt = jwt;
        this.mail = mail;
        this.rates = rates;
        this.denylist = denylist;
        this.transactions = transactions;
        this.secureCookie = secureCookie;
        this.publicUrl = publicUrl;
    }

    @Transactional
    public IssuedSession register(String email, String displayName, String password, String userAgent, String ip) {
        rates.hit("ratelimit:register:" + ip, 5, Duration.ofHours(1));
        String normalized = normalizeEmail(email);
        checkDisplayName(displayName);
        passwords.check(password);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, normalized) > 0) {
            throw new ApiException(409, "EMAIL_TAKEN", "An account with that email already exists.");
        }
        UUID userId = Ids.uuidV7();
        UUID workspaceId = Ids.uuidV7();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, display_name, status) VALUES (?, ?, ?, ?, 'pending_verification')",
                userId, normalized, passwords.hash(password), displayName.trim());
        jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)",
                workspaceId, displayName.trim() + "'s Workspace", userId);
        jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role) VALUES (?, ?, 'owner')",
                workspaceId, userId);
        jdbc.update(
                """
                UPDATE document_permissions
                SET principal_type = 'user', user_id = ?, email = NULL
                WHERE principal_type = 'email_invite' AND email = ? AND revoked_at IS NULL
                """,
                userId, normalized);
        sendToken(userId, normalized, "verify_email", "Verify your CollabDocs email",
                publicUrl + "/verify-email?token=");
        return issue(userId, false, userAgent, ip, true);
    }

    @Transactional
    public void verify(String token) {
        UUID userId = consumeToken(token, "verify_email");
        jdbc.update("UPDATE users SET status = 'active', email_verified_at = now(), updated_at = now() WHERE id = ? AND status = 'pending_verification'", userId);
    }

    @Transactional
    public void resend(UUID userId) {
        rates.hit("ratelimit:resend:" + userId, 3, Duration.ofHours(1));
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        jdbc.update("UPDATE email_tokens SET consumed_at = now() WHERE user_id = ? AND purpose = 'verify_email' AND consumed_at IS NULL", userId);
        sendToken(userId, email, "verify_email", "Verify your CollabDocs email", publicUrl + "/verify-email?token=");
    }

    @Transactional
    public IssuedSession login(String email, String password, String userAgent, String ip) {
        rates.hit("ratelimit:login:" + ip, 10, Duration.ofMinutes(1));
        var users = jdbc.query("SELECT id, password_hash, status, email_verified_at, purge_after FROM users WHERE email = ?",
                (rs, row) -> new LoginRow(rs.getObject("id", UUID.class), rs.getString("password_hash"), rs.getString("status"),
                        rs.getTimestamp("email_verified_at") != null, rs.getTimestamp("purge_after")),
                normalizeEmail(email));
        if (users.isEmpty() || !passwords.matches(password, users.get(0).hash())) {
            throw new ApiException(401, "INVALID_CREDENTIALS", "Email or password is incorrect.");
        }
        LoginRow user = users.get(0);
        if ("disabled".equals(user.status()) || "deleted".equals(user.status())) {
            throw new ApiException(403, "ACCOUNT_DISABLED", "This account is disabled.");
        }
        if ("pending_deletion".equals(user.status())) {
            if (user.purgeAfter() != null && user.purgeAfter().toInstant().isBefore(Instant.now())) {
                throw new ApiException(403, "ACCOUNT_DISABLED", "This account is disabled.");
            }
            String restored = user.verified() ? "active" : "pending_verification";
            jdbc.update("UPDATE users SET status = ?, purge_after = NULL, updated_at = now() WHERE id = ?", restored, user.id());
        }
        return issue(user.id(), user.verified() || "active".equals(user.status()), userAgent, ip, false);
    }

    @Transactional
    public IssuedSession refresh(String rawToken, String userAgent, String ip) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
        }
        String hash = Ids.sha256(rawToken);
        var rows = jdbc.query("SELECT id, user_id, family_id, expires_at, revoked_at FROM refresh_sessions WHERE token_hash = ?",
                (rs, row) -> new SessionRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") != null), hash);
        if (rows.isEmpty()) {
            throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
        }
        SessionRow session = rows.get(0);
        if (session.revoked()) {
            revokeFamilyNow(session.familyId());
            throw new ApiException(401, "SESSION_REUSED", "This session was revoked. Sign in again.");
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(401, "UNAUTHENTICATED", "Sign in again.");
        }
        UUID nextId = Ids.uuidV7();
        jdbc.update("UPDATE refresh_sessions SET revoked_at = now(), replaced_by = ? WHERE id = ?", nextId, session.id());
        boolean verified = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT email_verified_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, session.userId()));
        String nextRaw = Ids.randomToken();
        jdbc.update(
                """
                INSERT INTO refresh_sessions (id, user_id, family_id, token_hash, expires_at, user_agent, ip)
                VALUES (?, ?, ?, ?, now() + interval '30 days', ?, CAST(? AS inet))
                """,
                nextId, session.userId(), session.familyId(), Ids.sha256(nextRaw), trim(userAgent), ipOrNull(ip));
        denylist.deny(session.id());
        return new IssuedSession(jwt.issue(session.userId(), nextId, verified), nextRaw, null, null);
    }

    @Transactional
    public void logout(UUID sessionId) {
        jdbc.update("UPDATE refresh_sessions SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", sessionId);
        denylist.deny(sessionId);
    }

    @Transactional
    public void logoutAll(UUID userId) {
        var ids = jdbc.query("SELECT id FROM refresh_sessions WHERE user_id = ? AND revoked_at IS NULL",
                (rs, row) -> rs.getObject(1, UUID.class), userId);
        jdbc.update("UPDATE refresh_sessions SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId);
        ids.forEach(denylist::deny);
    }

    @Transactional
    public void forgot(String email, String ip) {
        rates.hit("ratelimit:forgot:" + ip, 5, Duration.ofHours(1));
        var ids = jdbc.query("SELECT id FROM users WHERE email = ? AND status <> 'deleted'",
                (rs, row) -> rs.getObject(1, UUID.class), normalizeEmail(email));
        if (!ids.isEmpty()) {
            sendToken(ids.get(0), normalizeEmail(email), "reset_password", "Reset your CollabDocs password",
                    publicUrl + "/reset-password?token=");
        }
    }

    @Transactional
    public void reset(String token, String password) {
        passwords.check(password);
        UUID userId = consumeToken(token, "reset_password");
        jdbc.update("UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?", passwords.hash(password), userId);
        logoutAll(userId);
    }

    public Map<String, Object> me(UUID userId) {
        return jdbc.queryForObject(
                """
                SELECT id, email, display_name, status, email_verified_at, avatar_object_key
                FROM users WHERE id = ?
                """,
                (rs, row) -> {
                    boolean avatar = rs.getString("avatar_object_key") != null;
                    return Map.of(
                            "id", rs.getObject("id", UUID.class),
                            "email", rs.getString("email"),
                            "displayName", rs.getString("display_name"),
                            "emailVerified", rs.getTimestamp("email_verified_at") != null,
                            "status", rs.getString("status"),
                            "avatarUrl", avatar ? "/api/v1/me/avatar" : "");
                }, userId);
    }

    public void updateName(UUID userId, String displayName) {
        checkDisplayName(displayName);
        jdbc.update("UPDATE users SET display_name = ?, updated_at = now() WHERE id = ?", displayName.trim(), userId);
    }

    @Transactional
    public void scheduleDeletion(UUID userId, String password, String confirmation) {
        if (!"DELETE".equals(confirmation)) {
            throw new ApiException(400, "VALIDATION_FAILED", "Type DELETE to confirm.");
        }
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        if (!passwords.matches(password, hash)) {
            throw new ApiException(403, "FORBIDDEN", "Password is incorrect.");
        }
        Boolean blocked = jdbc.queryForObject(
                """
                SELECT EXISTS(
                  SELECT 1 FROM workspaces w
                  JOIN workspace_memberships others ON others.workspace_id = w.id AND others.user_id <> w.owner_id
                  WHERE w.owner_id = ?
                )
                """, Boolean.class, userId);
        if (Boolean.TRUE.equals(blocked)) {
            throw new ApiException(409, "WORKSPACE_OWNERSHIP", "Transfer workspace ownership before deleting this account.");
        }
        jdbc.update("UPDATE users SET status = 'pending_deletion', purge_after = now() + interval '7 days', updated_at = now() WHERE id = ?", userId);
    }

    public List<Map<String, Object>> sessions(UUID userId, UUID currentSession) {
        return jdbc.query(
                """
                SELECT id, created_at, last_used_at, user_agent, host(ip) AS ip
                FROM refresh_sessions WHERE user_id = ? AND revoked_at IS NULL AND expires_at > now()
                ORDER BY created_at DESC
                """,
                (rs, row) -> Map.of(
                        "id", rs.getObject("id", UUID.class),
                        "createdAt", rs.getTimestamp("created_at").toInstant().toString(),
                        "lastUsedAt", rs.getTimestamp("last_used_at").toInstant().toString(),
                        "userAgent", rs.getString("user_agent") == null ? "" : rs.getString("user_agent"),
                        "ipPrefix", prefix(rs.getString("ip")),
                        "current", rs.getObject("id", UUID.class).equals(currentSession)),
                userId);
    }

    public void revokeSession(UUID userId, UUID sessionId) {
        int updated = jdbc.update(
                "UPDATE refresh_sessions SET revoked_at = now() WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                sessionId, userId);
        if (updated == 0) {
            throw new ApiException(404, "NOT_FOUND", "Session is not available.");
        }
        denylist.deny(sessionId);
    }

    public ResponseCookie cookie(String raw) {
        return ResponseCookie.from("cd_refresh", raw == null ? "" : raw)
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/api/v1/auth")
                .maxAge(raw == null ? Duration.ZERO : Duration.ofDays(30)).build();
    }

    public HttpHeaders cookieHeader(String raw) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookie(raw).toString());
        return headers;
    }

    private IssuedSession issue(UUID userId, boolean verified, String userAgent, String ip, boolean includeWorkspace) {
        UUID sessionId = Ids.uuidV7();
        UUID familyId = Ids.uuidV7();
        String raw = Ids.randomToken();
        jdbc.update(
                """
                INSERT INTO refresh_sessions (id, user_id, family_id, token_hash, expires_at, user_agent, ip)
                VALUES (?, ?, ?, ?, now() + interval '30 days', ?, CAST(? AS inet))
                """,
                sessionId, userId, familyId, Ids.sha256(raw), trim(userAgent), ipOrNull(ip));
        Map<String, Object> user = me(userId);
        Map<String, Object> workspace = null;
        if (includeWorkspace) {
            workspace = jdbc.queryForObject(
                    """
                    SELECT w.id, w.name, m.role, w.link_sharing
                    FROM workspaces w JOIN workspace_memberships m ON m.workspace_id = w.id
                    WHERE m.user_id = ? ORDER BY w.created_at ASC LIMIT 1
                    """,
                    (rs, row) -> Map.of("id", rs.getObject("id", UUID.class), "name", rs.getString("name"),
                            "role", rs.getString("role"), "linkSharing", rs.getString("link_sharing")),
                    userId);
        }
        return new IssuedSession(jwt.issue(userId, sessionId, verified), raw, user, workspace);
    }

    private void sendToken(UUID userId, String email, String purpose, String subject, String prefix) {
        String raw = Ids.randomToken();
        jdbc.update(
                "INSERT INTO email_tokens (id, user_id, purpose, token_hash, expires_at) VALUES (?, ?, ?, ?, ?)",
                Ids.uuidV7(), userId, purpose, Ids.sha256(raw),
                Timestamp.from("reset_password".equals(purpose) ? Instant.now().plus(Duration.ofMinutes(30)) : Instant.now().plus(Duration.ofHours(24))));
        mail.send(email, subject, prefix + raw);
    }

    private UUID consumeToken(String raw, String purpose) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "TOKEN_INVALID", "This link is invalid or expired.");
        }
        var rows = jdbc.query(
                """
                UPDATE email_tokens SET consumed_at = now()
                WHERE token_hash = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > now()
                RETURNING user_id
                """,
                (rs, row) -> rs.getObject(1, UUID.class), Ids.sha256(raw), purpose);
        if (rows.isEmpty()) {
            throw new ApiException(400, "TOKEN_INVALID", "This link is invalid or expired.");
        }
        return rows.get(0);
    }

    private void revokeFamilyNow(UUID familyId) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> revokeFamily(familyId));
    }

    private void revokeFamily(UUID familyId) {
        var ids = jdbc.query("SELECT id FROM refresh_sessions WHERE family_id = ? AND revoked_at IS NULL",
                (rs, row) -> rs.getObject(1, UUID.class), familyId);
        jdbc.update("UPDATE refresh_sessions SET revoked_at = now() WHERE family_id = ?", familyId);
        ids.forEach(denylist::deny);
    }

    private static void checkDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty() || displayName.trim().length() > 80
                || displayName.chars().anyMatch(ch -> Character.isISOControl(ch))) {
            throw new ApiException(400, "VALIDATION_FAILED", "Display name is invalid.",
                    List.of(new ApiException.FieldDetail("displayName", "invalid")));
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.length() > 254 || !email.contains("@")) {
            throw new ApiException(400, "VALIDATION_FAILED", "Email is invalid.",
                    List.of(new ApiException.FieldDetail("email", "invalid")));
        }
        return email.trim().toLowerCase();
    }

    private static String trim(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > 300 ? userAgent.substring(0, 300) : userAgent;
    }

    private static String ipOrNull(String ip) {
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            return null;
        }
        return ip;
    }

    private static String prefix(String ip) {
        if (ip == null || !ip.contains(".")) {
            return "";
        }
        String[] parts = ip.split("\\.");
        return parts.length < 3 ? ip : parts[0] + "." + parts[1] + "." + parts[2];
    }

    public record IssuedSession(String accessToken, String refreshToken, Map<String, Object> user, Map<String, Object> workspace) {}

    private record LoginRow(UUID id, String hash, String status, boolean verified, Timestamp purgeAfter) {}

    private record SessionRow(UUID id, UUID userId, UUID familyId, Instant expiresAt, boolean revoked) {}
}
