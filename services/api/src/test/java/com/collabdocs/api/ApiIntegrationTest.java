package com.collabdocs.api;

import com.collabdocs.api.auth.CapturingMail;
import com.collabdocs.persistence.AccountPurgeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ApiIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        registry.add("collabdocs.jwt.private-key", () -> pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        registry.add("collabdocs.jwt.public-key", () -> pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        Path migration = Path.of("..", "..", "db", "migration");
        if (!Files.isDirectory(migration)) {
            migration = Path.of("db", "migration");
        }
        String location = migration.toAbsolutePath().toString();
        registry.add("spring.flyway.locations", () -> "filesystem:" + location);
    }

    @Autowired TestRestTemplate rest;
    @Autowired CapturingMail mail;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountPurgeService purgeService;

    @BeforeEach
    void clearMail() {
        mail.clear();
    }

    @Test
    void registerDuplicateAndVerificationGate() {
        String email = "priya-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> created = post("/api/v1/auth/register", Map.of(
                "email", email, "displayName", "Priya N", "password", "correct-horse-1"), null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String token = created.getBody().get("accessToken").toString();
        ResponseEntity<Map> duplicate = post("/api/v1/auth/register", Map.of(
                "email", email, "displayName", "Priya N", "password", "correct-horse-1"), null);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());

        String workspaceId = ((Map<?, ?>) created.getBody().get("workspace")).get("id").toString();
        ResponseEntity<Map> blocked = post("/api/v1/workspaces/" + workspaceId + "/documents", Map.of(), token);
        assertEquals(HttpStatus.FORBIDDEN, blocked.getStatusCode());

        String raw = mail.messages.get(0).text().substring(mail.messages.get(0).text().indexOf("token=") + 6).trim();
        ResponseEntity<Map> verified = post("/api/v1/auth/verify-email", Map.of("token", raw), null);
        assertEquals(HttpStatus.OK, verified.getStatusCode());
        ResponseEntity<Map> login = post("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-1"), null);
        String verifiedToken = login.getBody().get("accessToken").toString();
        ResponseEntity<Map> document = post("/api/v1/workspaces/" + workspaceId + "/documents", Map.of("title", "Notes"), verifiedToken);
        assertEquals(HttpStatus.CREATED, document.getStatusCode());
        String documentId = document.getBody().get("id").toString();

        String otherEmail = "omar-" + UUID.randomUUID() + "@example.com";
        post("/api/v1/auth/register", Map.of("email", otherEmail, "displayName", "Omar K", "password", "correct-horse-1"), null);
        String otherRaw = mail.messages.get(mail.messages.size() - 1).text();
        otherRaw = otherRaw.substring(otherRaw.indexOf("token=") + 6).trim();
        post("/api/v1/auth/verify-email", Map.of("token", otherRaw), null);
        ResponseEntity<Map> otherLogin = post("/api/v1/auth/login", Map.of("email", otherEmail, "password", "correct-horse-1"), null);
        ResponseEntity<Map> hidden = exchange("/api/v1/documents/" + documentId, HttpMethod.GET, null,
                otherLogin.getBody().get("accessToken").toString());
        assertEquals(HttpStatus.NOT_FOUND, hidden.getStatusCode());
    }

    @Test
    void refreshReuseAndLogout() {
        String email = "session-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> created = post("/api/v1/auth/register", Map.of(
                "email", email, "displayName", "Session User", "password", "correct-horse-1"), null);
        String firstCookie = cookie(created);
        ResponseEntity<Map> rotated = post("/api/v1/auth/refresh", Map.of(), null, firstCookie);
        assertEquals(HttpStatus.OK, rotated.getStatusCode());
        String secondCookie = cookie(rotated);
        ResponseEntity<Map> reused = post("/api/v1/auth/refresh", Map.of(), null, firstCookie);
        assertEquals(HttpStatus.UNAUTHORIZED, reused.getStatusCode());
        ResponseEntity<Map> familyDead = post("/api/v1/auth/refresh", Map.of(), null, secondCookie);
        assertEquals(HttpStatus.UNAUTHORIZED, familyDead.getStatusCode());

        ResponseEntity<Map> login = post("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-1"), null);
        String access = login.getBody().get("accessToken").toString();
        exchange("/api/v1/auth/logout", HttpMethod.POST, Map.of(), access);
        ResponseEntity<Map> after = exchange("/api/v1/me", HttpMethod.GET, null, access);
        assertEquals(HttpStatus.UNAUTHORIZED, after.getStatusCode());
    }

    @Test
    void passwordResetRevokesSessions() {
        String email = "reset-" + UUID.randomUUID() + "@example.com";
        post("/api/v1/auth/register", Map.of("email", email, "displayName", "Reset User", "password", "correct-horse-1"), null);
        mail.clear();
        post("/api/v1/auth/password/forgot", Map.of("email", email), null);
        String raw = mail.messages.get(0).text();
        raw = raw.substring(raw.indexOf("token=") + 6).trim();
        ResponseEntity<Map> reset = post("/api/v1/auth/password/reset", Map.of("token", raw, "password", "correct-horse-2"), null);
        assertEquals(HttpStatus.NO_CONTENT, reset.getStatusCode());
        ResponseEntity<Map> oldPassword = post("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-1"), null);
        assertEquals(HttpStatus.UNAUTHORIZED, oldPassword.getStatusCode());
        ResponseEntity<Map> next = post("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-2"), null);
        assertEquals(HttpStatus.OK, next.getStatusCode());
    }

    @Test
    void contentUpdateIsIdempotentAndSurvivesLookup() {
        var owner = verifiedUser();
        ResponseEntity<Map> document = post("/api/v1/workspaces/" + owner.workspaceId + "/documents", Map.of("title", "Draft"), owner.token);
        String documentId = document.getBody().get("id").toString();
        Map<String, Object> update = Map.of(
                "clientId", UUID.randomUUID().toString(),
                "updateId", UUID.randomUUID().toString(),
                "epoch", 1,
                "intent", "edit",
                "payloadBase64", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}));
        ResponseEntity<Map> first = post("/api/v1/documents/" + documentId + "/content-updates", update, owner.token);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(false, first.getBody().get("duplicate"));
        ResponseEntity<Map> second = post("/api/v1/documents/" + documentId + "/content-updates", update, owner.token);
        assertEquals(true, second.getBody().get("duplicate"));
        assertEquals(first.getBody().get("seq"), second.getBody().get("seq"));
        ResponseEntity<Map> state = exchange("/api/v1/documents/" + documentId + "/content-state", HttpMethod.GET, null, owner.token);
        assertEquals(HttpStatus.OK, state.getStatusCode());
        assertFalse(((java.util.List<?>) state.getBody().get("updates")).isEmpty());
    }

    @Test
    void folderCycleAndSearchIsolation() {
        var owner = verifiedUser();
        ResponseEntity<Map> parent = post("/api/v1/workspaces/" + owner.workspaceId + "/folders", Map.of("name", "Parent"), owner.token);
        ResponseEntity<Map> child = post("/api/v1/workspaces/" + owner.workspaceId + "/folders",
                Map.of("name", "Child", "parentId", parent.getBody().get("id")), owner.token);
        ResponseEntity<Map> cycle = exchange("/api/v1/folders/" + parent.getBody().get("id"), HttpMethod.PATCH,
                Map.of("parentId", child.getBody().get("id")), owner.token);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, cycle.getStatusCode());
        post("/api/v1/workspaces/" + owner.workspaceId + "/documents", Map.of("title", "Secret Notes"), owner.token);
        var other = verifiedUser();
        ResponseEntity<Map> search = exchange("/api/v1/workspaces/" + other.workspaceId + "/search?q=Secret", HttpMethod.GET, null, other.token);
        assertTrue(((java.util.List<?>) search.getBody().get("items")).isEmpty());
    }

    @Test
    void deletionIsScheduledAndPurged() {
        var owner = verifiedUser();
        post("/api/v1/workspaces/" + owner.workspaceId + "/documents", Map.of("title", "Gone"), owner.token);
        ResponseEntity<Map> scheduled = exchange("/api/v1/me", HttpMethod.DELETE,
                Map.of("password", "correct-horse-1", "confirmation", "DELETE"), owner.token);
        assertEquals(HttpStatus.ACCEPTED, scheduled.getStatusCode());
        jdbc.update("UPDATE users SET purge_after = now() - interval '1 minute' WHERE id = ?::uuid", owner.userId);
        assertTrue(purgeService.purgeDue() >= 1);
        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE owner_id = ?::uuid", Integer.class, owner.userId);
        assertEquals(0, left);
    }

    private User verifiedUser() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> created = post("/api/v1/auth/register", Map.of(
                "email", email, "displayName", "Test User", "password", "correct-horse-1"), null);
        String raw = mail.messages.get(mail.messages.size() - 1).text();
        raw = raw.substring(raw.indexOf("token=") + 6).trim();
        post("/api/v1/auth/verify-email", Map.of("token", raw), null);
        ResponseEntity<Map> login = post("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-1"), null);
        String workspaceId = ((Map<?, ?>) created.getBody().get("workspace")).get("id").toString();
        String userId = ((Map<?, ?>) created.getBody().get("user")).get("id").toString();
        return new User(email, userId, workspaceId, login.getBody().get("accessToken").toString());
    }

    private ResponseEntity<Map> post(String path, Object body, String token) {
        return post(path, body, token, null);
    }

    private ResponseEntity<Map> post(String path, Object body, String token, String cookie) {
        return exchange(path, HttpMethod.POST, body, token, cookie);
    }

    private ResponseEntity<Map> exchange(String path, HttpMethod method, Object body, String token) {
        return exchange(path, method, body, token, null);
    }

    private ResponseEntity<Map> exchange(String path, HttpMethod method, Object body, String token, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-CollabDocs-Request", "1");
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, "cd_refresh=" + cookie);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    private static String cookie(ResponseEntity<?> response) {
        String header = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        int start = header.indexOf("cd_refresh=") + "cd_refresh=".length();
        int end = header.indexOf(';', start);
        return header.substring(start, end);
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }

    private record User(String email, String userId, String workspaceId, String token) {}
}
