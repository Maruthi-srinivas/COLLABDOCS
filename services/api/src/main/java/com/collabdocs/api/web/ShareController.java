package com.collabdocs.api.web;

import com.collabdocs.api.docs.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
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
public class ShareController {
    private final ShareService shares;

    public ShareController(ShareService shares) {
        this.shares = shares;
    }

    @GetMapping("/api/v1/documents/{documentId}/permissions")
    Map<String, Object> list(HttpServletRequest request, @PathVariable UUID documentId) {
        return Map.of("items", shares.list(AuthController.current(request).userId(), documentId));
    }

    @PostMapping("/api/v1/documents/{documentId}/permissions")
    ResponseEntity<Map<String, Object>> grant(HttpServletRequest request, @PathVariable UUID documentId,
                                               @RequestBody Map<String, String> body) {
        String user = body.get("userId");
        return ResponseEntity.status(HttpStatus.CREATED).body(shares.grant(
                AuthController.current(request).userId(), documentId,
                user == null || user.isBlank() ? null : UUID.fromString(user),
                body.get("email"), body.get("role")));
    }

    @PatchMapping("/api/v1/documents/{documentId}/permissions/{permissionId}")
    Map<String, Object> role(HttpServletRequest request, @PathVariable UUID documentId, @PathVariable UUID permissionId,
                             @RequestBody Map<String, String> body) {
        return shares.changeRole(AuthController.current(request).userId(), documentId, permissionId, body.get("role"));
    }

    @DeleteMapping("/api/v1/documents/{documentId}/permissions/{permissionId}")
    ResponseEntity<Void> revoke(HttpServletRequest request, @PathVariable UUID documentId, @PathVariable UUID permissionId) {
        shares.revoke(AuthController.current(request).userId(), documentId, permissionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/documents/{documentId}/transfer-ownership")
    Map<String, Object> transfer(HttpServletRequest request, @PathVariable UUID documentId, @RequestBody Map<String, String> body) {
        return shares.transfer(AuthController.current(request).userId(), documentId, UUID.fromString(body.get("userId")));
    }

    @GetMapping("/api/v1/documents/{documentId}/share-links")
    Map<String, Object> links(HttpServletRequest request, @PathVariable UUID documentId) {
        return Map.of("items", shares.links(AuthController.current(request).userId(), documentId));
    }

    @PostMapping("/api/v1/documents/{documentId}/share-links")
    ResponseEntity<Map<String, Object>> createLink(HttpServletRequest request, @PathVariable UUID documentId,
                                                    @RequestBody Map<String, String> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shares.createLink(
                AuthController.current(request).userId(), documentId, body.get("role"), body.get("expiresAt")));
    }

    @DeleteMapping("/api/v1/share-links/{linkId}")
    ResponseEntity<Void> revokeLink(HttpServletRequest request, @PathVariable UUID linkId) {
        shares.revokeLink(AuthController.current(request).userId(), linkId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/share-links/{token}/redeem")
    Map<String, Object> redeem(HttpServletRequest request, @PathVariable String token) {
        return shares.redeem(AuthController.current(request).userId(), token);
    }

    @PostMapping("/api/v1/documents/{documentId}/access-requests")
    ResponseEntity<Map<String, Object>> request(HttpServletRequest request, @PathVariable UUID documentId) {
        Map<String, Object> body = shares.requestAccess(AuthController.current(request).userId(), documentId);
        return ResponseEntity.status(Boolean.TRUE.equals(body.get("existing")) ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    @PostMapping("/api/v1/access-requests/{requestId}/approve")
    Map<String, Object> approve(HttpServletRequest request, @PathVariable UUID requestId, @RequestBody Map<String, String> body) {
        return shares.decide(AuthController.current(request).userId(), requestId, true, body.get("role"));
    }

    @PostMapping("/api/v1/access-requests/{requestId}/deny")
    Map<String, Object> deny(HttpServletRequest request, @PathVariable UUID requestId) {
        return shares.decide(AuthController.current(request).userId(), requestId, false, null);
    }
}
