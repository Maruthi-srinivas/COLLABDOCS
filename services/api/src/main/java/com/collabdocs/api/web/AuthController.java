package com.collabdocs.api.web;

import com.collabdocs.api.auth.AuthService;
import com.collabdocs.api.auth.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/api/v1/auth/register")
    ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body, HttpServletRequest request) {
        var session = auth.register(body.get("email"), body.get("displayName"), body.get("password"),
                request.getHeader("User-Agent"), ApiErrors.clientIp(request));
        return ResponseEntity.status(HttpStatus.CREATED).headers(auth.cookieHeader(session.refreshToken()))
                .body(sessionBody(session));
    }

    @PostMapping("/api/v1/auth/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        var session = auth.login(body.get("email"), body.get("password"), request.getHeader("User-Agent"),
                ApiErrors.clientIp(request));
        return ResponseEntity.ok().headers(auth.cookieHeader(session.refreshToken())).body(sessionBody(session));
    }

    @PostMapping("/api/v1/auth/refresh")
    ResponseEntity<Map<String, Object>> refresh(HttpServletRequest request) {
        var session = auth.refresh(ApiGuardFilter.cookie(request, "cd_refresh"), request.getHeader("User-Agent"),
                ApiErrors.clientIp(request));
        return ResponseEntity.ok().headers(auth.cookieHeader(session.refreshToken()))
                .body(Map.of("accessToken", session.accessToken(), "expiresIn", 900));
    }

    @PostMapping("/api/v1/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(current(request).sessionId());
        return ResponseEntity.noContent().headers(auth.cookieHeader(null)).build();
    }

    @PostMapping("/api/v1/auth/logout-all")
    ResponseEntity<Void> logoutAll(HttpServletRequest request) {
        auth.logoutAll(current(request).userId());
        return ResponseEntity.noContent().headers(auth.cookieHeader(null)).build();
    }

    @PostMapping("/api/v1/auth/verify-email")
    Map<String, String> verify(@RequestBody Map<String, String> body) {
        auth.verify(body.get("token"));
        return Map.of("status", "active");
    }

    @PostMapping("/api/v1/auth/verify-email/resend")
    ResponseEntity<Void> resend(HttpServletRequest request) {
        auth.resend(current(request).userId());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/v1/auth/password/forgot")
    ResponseEntity<Void> forgot(@RequestBody Map<String, String> body, HttpServletRequest request) {
        auth.forgot(body.get("email"), ApiErrors.clientIp(request));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/v1/auth/password/reset")
    ResponseEntity<Void> reset(@RequestBody Map<String, String> body) {
        auth.reset(body.get("token"), body.get("password"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/auth/sessions")
    Map<String, Object> sessions(HttpServletRequest request) {
        var user = current(request);
        return Map.of("items", auth.sessions(user.userId(), user.sessionId()));
    }

    @DeleteMapping("/api/v1/auth/sessions/{sessionId}")
    ResponseEntity<Void> revoke(HttpServletRequest request, @PathVariable java.util.UUID sessionId) {
        auth.revokeSession(current(request).userId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/me")
    Map<String, Object> me(HttpServletRequest request) {
        return auth.me(current(request).userId());
    }

    @PatchMapping("/api/v1/me")
    Map<String, Object> updateMe(HttpServletRequest request, @RequestBody Map<String, String> body) {
        auth.updateName(current(request).userId(), body.get("displayName"));
        return auth.me(current(request).userId());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/me")
    ResponseEntity<Map<String, Object>> deleteMe(HttpServletRequest request, @RequestBody Map<String, String> body) {
        var user = current(request);
        auth.scheduleDeletion(user.userId(), body.get("password"), body.get("confirmation"));
        return ResponseEntity.accepted().body(Map.of("purgeAfter", java.time.Instant.now().plus(java.time.Duration.ofDays(7)).toString()));
    }

    private static Map<String, Object> sessionBody(AuthService.IssuedSession session) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("accessToken", session.accessToken());
        body.put("expiresIn", 900);
        body.put("user", session.user());
        if (session.workspace() != null) {
            body.put("workspace", session.workspace());
        }
        return body;
    }

    static JwtService.AccessClaims current(HttpServletRequest request) {
        return (JwtService.AccessClaims) request.getAttribute("currentUser");
    }
}
