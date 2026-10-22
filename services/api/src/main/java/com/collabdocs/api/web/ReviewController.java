package com.collabdocs.api.web;

import com.collabdocs.api.docs.CommentService;
import com.collabdocs.api.docs.NotificationService;
import com.collabdocs.api.docs.SuggestionService;
import com.collabdocs.api.docs.VersionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {
    private final CommentService comments;
    private final SuggestionService suggestions;
    private final VersionService versions;
    private final NotificationService notifications;

    public ReviewController(CommentService comments, SuggestionService suggestions, VersionService versions,
                            NotificationService notifications) {
        this.comments = comments;
        this.suggestions = suggestions;
        this.versions = versions;
        this.notifications = notifications;
    }

    @GetMapping("/api/v1/documents/{documentId}/threads")
    Map<String, Object> threads(HttpServletRequest request, @PathVariable UUID documentId, @RequestParam(required = false) String status) {
        return Map.of("items", comments.list(user(request), documentId, status));
    }

    @PostMapping("/api/v1/documents/{documentId}/threads")
    ResponseEntity<Map<String, Object>> createThread(HttpServletRequest request, @PathVariable UUID documentId,
                                                      @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(comments.create(
                user(request), documentId, text(body.get("body")), text(body.get("quotedText")),
                text(body.get("anchorStartBase64")), text(body.get("anchorEndBase64")), strings(body.get("mentionUserIds"))));
    }

    @PatchMapping("/api/v1/threads/{threadId}")
    Map<String, Object> anchor(HttpServletRequest request, @PathVariable UUID threadId, @RequestBody Map<String, String> body) {
        comments.anchor(user(request), threadId, body.get("anchorState"));
        return Map.of("id", threadId, "anchorState", body.get("anchorState"));
    }

    @PostMapping("/api/v1/threads/{threadId}/comments")
    ResponseEntity<Map<String, Object>> reply(HttpServletRequest request, @PathVariable UUID threadId, @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(comments.reply(user(request), threadId, text(body.get("body")), strings(body.get("mentionUserIds"))));
    }

    @PostMapping("/api/v1/threads/{threadId}/resolve")
    Map<String, Object> resolve(HttpServletRequest request, @PathVariable UUID threadId) {
        comments.resolve(user(request), threadId, false);
        return Map.of("id", threadId, "status", "resolved");
    }

    @PostMapping("/api/v1/threads/{threadId}/reopen")
    Map<String, Object> reopen(HttpServletRequest request, @PathVariable UUID threadId) {
        comments.resolve(user(request), threadId, true);
        return Map.of("id", threadId, "status", "open");
    }

    @PatchMapping("/api/v1/comments/{commentId}")
    Map<String, Object> editComment(HttpServletRequest request, @PathVariable UUID commentId, @RequestBody Map<String, String> body) {
        comments.edit(user(request), commentId, body.get("body"));
        return Map.of("id", commentId);
    }

    @DeleteMapping("/api/v1/comments/{commentId}")
    ResponseEntity<Void> deleteComment(HttpServletRequest request, @PathVariable UUID commentId) {
        comments.delete(user(request), commentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/documents/{documentId}/suggestions")
    Map<String, Object> suggestions(HttpServletRequest request, @PathVariable UUID documentId, @RequestParam(required = false) String status) {
        return Map.of("items", suggestions.list(user(request), documentId, status));
    }

    @PostMapping("/api/v1/documents/{documentId}/suggestions")
    ResponseEntity<Map<String, Object>> register(HttpServletRequest request, @PathVariable UUID documentId, @RequestBody Map<String, Object> body) {
        boolean created = suggestions.register(user(request), documentId, UUID.fromString(text(body.get("id"))), text(body.get("type")), body.get("payload"));
        Map<String, Object> response = Map.of("id", body.get("id"), "type", body.get("type"));
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }

    @PatchMapping("/api/v1/documents/{documentId}/suggestions/{suggestionId}")
    Map<String, Object> suggestionAnchor(HttpServletRequest request, @PathVariable UUID documentId, @PathVariable UUID suggestionId,
                                          @RequestBody Map<String, String> body) {
        suggestions.anchor(user(request), documentId, suggestionId, body.get("anchorState"));
        return Map.of("id", suggestionId, "anchorState", body.get("anchorState"));
    }

    @PostMapping("/api/v1/documents/{documentId}/suggestions/{suggestionId}/accept")
    Map<String, Object> accept(HttpServletRequest request, @PathVariable UUID documentId, @PathVariable UUID suggestionId,
                               @RequestBody Map<String, Object> body) {
        return suggestions.accept(user(request), documentId, suggestionId, body, true);
    }

    @PostMapping("/api/v1/documents/{documentId}/suggestions/{suggestionId}/reject")
    Map<String, Object> reject(HttpServletRequest request, @PathVariable UUID documentId, @PathVariable UUID suggestionId,
                               @RequestBody Map<String, Object> body) {
        return suggestions.accept(user(request), documentId, suggestionId, body, false);
    }

    @PostMapping("/api/v1/documents/{documentId}/suggestions/accept-all")
    Map<String, Object> acceptAll(HttpServletRequest request, @PathVariable UUID documentId, @RequestBody Map<String, Object> body) {
        return suggestions.acceptAll(user(request), documentId, updates(body), true);
    }

    @PostMapping("/api/v1/documents/{documentId}/suggestions/reject-all")
    Map<String, Object> rejectAll(HttpServletRequest request, @PathVariable UUID documentId, @RequestBody Map<String, Object> body) {
        return suggestions.acceptAll(user(request), documentId, updates(body), false);
    }

    @GetMapping("/api/v1/documents/{documentId}/versions")
    Map<String, Object> versions(HttpServletRequest request, @PathVariable UUID documentId) {
        return Map.of("items", versions.list(user(request), documentId));
    }

    @PostMapping("/api/v1/documents/{documentId}/versions")
    ResponseEntity<Map<String, Object>> name(HttpServletRequest request, @PathVariable UUID documentId, @RequestBody Map<String, String> body) {
        Map<String, Object> named = versions.name(user(request), documentId, body.get("label"));
        boolean ready = Boolean.TRUE.equals(named.get("ready"));
        return ResponseEntity.status(ready ? HttpStatus.CREATED : HttpStatus.ACCEPTED).body(named);
    }

    @DeleteMapping("/api/v1/documents/{documentId}/versions/{versionId}")
    ResponseEntity<Void> deleteVersion(HttpServletRequest request, @PathVariable UUID documentId, @PathVariable UUID versionId) {
        versions.deleteNamed(user(request), documentId, versionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/api/v1/documents/{documentId}/versions/{versionId}/preview", produces = MediaType.TEXT_HTML_VALUE)
    String preview(HttpServletRequest request, @PathVariable UUID documentId, @PathVariable UUID versionId) throws Exception {
        return versions.preview(user(request), documentId, versionId);
    }

    @GetMapping("/api/v1/documents/{documentId}/versions/compare")
    Map<String, Object> compare(HttpServletRequest request, @PathVariable UUID documentId, @RequestParam UUID from, @RequestParam UUID to) throws Exception {
        return versions.compare(user(request), documentId, from, to);
    }

    @PostMapping("/api/v1/documents/{documentId}/versions/{versionId}/restore")
    Map<String, Object> restore(HttpServletRequest request, @PathVariable UUID documentId, @PathVariable UUID versionId,
                                @RequestBody Map<String, Object> body) {
        return versions.restore(user(request), documentId, versionId, Boolean.TRUE.equals(body.get("confirm")));
    }

    @GetMapping("/api/v1/notifications")
    Map<String, Object> notifications(HttpServletRequest request, @RequestParam(required = false) Boolean unread) {
        return Map.of("items", notifications.list(user(request), Boolean.TRUE.equals(unread)));
    }

    @PostMapping("/api/v1/notifications/{notificationId}/read")
    ResponseEntity<Void> read(HttpServletRequest request, @PathVariable UUID notificationId) {
        notifications.markRead(user(request), notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/notifications/read-all")
    ResponseEntity<Void> readAll(HttpServletRequest request) {
        notifications.markAllRead(user(request));
        return ResponseEntity.noContent().build();
    }

    private static UUID user(HttpServletRequest request) {
        return AuthController.current(request).userId();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> updates(Map<String, Object> body) {
        Object value = body.get("updates");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map((item) -> (Map<String, Object>) item).toList();
    }
}
