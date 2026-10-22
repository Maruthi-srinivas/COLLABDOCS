package com.collabdocs.api.web;

import com.collabdocs.api.docs.DocumentService;
import com.collabdocs.api.docs.FolderService;
import com.collabdocs.common.ApiException;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocumentController {
    private final DocumentService documents;
    private final FolderService folders;

    public DocumentController(DocumentService documents, FolderService folders) {
        this.documents = documents;
        this.folders = folders;
    }

    @GetMapping("/api/v1/workspaces")
    Map<String, Object> workspaces(HttpServletRequest request) {
        return Map.of("items", documents.workspaces(AuthController.current(request).userId()));
    }

    @PostMapping("/api/v1/workspaces")
    void createWorkspace() {
        throw new ApiException(403, "WORKSPACE_CREATE_DISABLED", "Extra workspaces are not available yet.");
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/folders")
    Map<String, Object> folders(HttpServletRequest request, @PathVariable UUID workspaceId,
                                @RequestParam(required = false) UUID parentId) {
        return Map.of("items", folders.list(AuthController.current(request).userId(), workspaceId, parentId));
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/folders")
    ResponseEntity<Map<String, Object>> createFolder(HttpServletRequest request, @PathVariable UUID workspaceId,
                                                      @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(folders.create(
                AuthController.current(request).userId(), workspaceId, string(body.get("name")), uuid(body.get("parentId"))));
    }

    @PatchMapping("/api/v1/folders/{folderId}")
    Map<String, Object> updateFolder(HttpServletRequest request, @PathVariable UUID folderId, @RequestBody Map<String, Object> body) {
        return folders.update(AuthController.current(request).userId(), folderId, string(body.get("name")),
                uuid(body.get("parentId")), body.containsKey("parentId"));
    }

    @DeleteMapping("/api/v1/folders/{folderId}")
    Map<String, Object> trashFolder(HttpServletRequest request, @PathVariable UUID folderId) {
        return folders.trash(AuthController.current(request).userId(), folderId);
    }

    @PostMapping("/api/v1/folders/{folderId}/restore")
    ResponseEntity<Void> restoreFolder(HttpServletRequest request, @PathVariable UUID folderId) {
        folders.restore(AuthController.current(request).userId(), folderId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/documents")
    Map<String, Object> documents(HttpServletRequest request, @PathVariable UUID workspaceId,
                                  @RequestParam(required = false) UUID folderId,
                                  @RequestParam(required = false) String sort,
                                  @RequestParam(required = false) String direction,
                                  @RequestParam(defaultValue = "50") int limit) {
        return documents.list(AuthController.current(request).userId(), workspaceId, folderId, sort, direction, limit);
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/documents")
    ResponseEntity<Map<String, Object>> create(HttpServletRequest request, @PathVariable UUID workspaceId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return ResponseEntity.status(HttpStatus.CREATED).body(documents.create(
                AuthController.current(request).userId(), workspaceId, string(payload.get("title")),
                uuid(payload.get("folderId")), uuid(payload.get("templateId"))));
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/documents/from-template")
    ResponseEntity<Map<String, Object>> fromTemplate(HttpServletRequest request, @PathVariable UUID workspaceId,
                                                      @RequestBody Map<String, Object> body) {
        UUID templateId = uuid(body.get("templateId"));
        if (templateId == null) throw new ApiException(400, "VALIDATION_FAILED", "Choose a template.");
        return ResponseEntity.status(HttpStatus.CREATED).body(documents.create(
                AuthController.current(request).userId(), workspaceId, string(body.get("title")),
                uuid(body.get("folderId")), templateId));
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/documents/recent")
    Map<String, Object> recent(HttpServletRequest request, @PathVariable UUID workspaceId) {
        return documents.recent(AuthController.current(request).userId(), workspaceId);
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/documents/starred")
    Map<String, Object> starred(HttpServletRequest request, @PathVariable UUID workspaceId) {
        return documents.starred(AuthController.current(request).userId(), workspaceId);
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/trash")
    Map<String, Object> trash(HttpServletRequest request, @PathVariable UUID workspaceId) {
        return documents.trashList(AuthController.current(request).userId(), workspaceId);
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/search")
    Map<String, Object> search(HttpServletRequest request, @PathVariable UUID workspaceId, @RequestParam String q) {
        return documents.search(AuthController.current(request).userId(), workspaceId, q);
    }

    @GetMapping("/api/v1/documents/{documentId}")
    Map<String, Object> get(HttpServletRequest request, @PathVariable UUID documentId) {
        return documents.get(AuthController.current(request).userId(), documentId);
    }

    @PatchMapping("/api/v1/documents/{documentId}")
    Map<String, Object> rename(HttpServletRequest request, @PathVariable UUID documentId,
                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                               @RequestBody Map<String, Object> body) {
        return documents.update(AuthController.current(request).userId(), documentId, body, ifMatch);
    }

    @PostMapping("/api/v1/documents/{documentId}/move")
    Map<String, Object> move(HttpServletRequest request, @PathVariable UUID documentId, @RequestBody Map<String, Object> body) {
        return documents.move(AuthController.current(request).userId(), documentId, uuid(body.get("folderId")));
    }

    @PostMapping("/api/v1/documents/{documentId}/duplicate")
    ResponseEntity<Map<String, Object>> duplicate(HttpServletRequest request, @PathVariable UUID documentId,
                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documents.duplicate(
                AuthController.current(request).userId(), documentId, UUID.fromString(String.valueOf(body.get("workspaceId"))),
                uuid(body.get("folderId")), string(body.get("title"))));
    }

    @DeleteMapping("/api/v1/documents/{documentId}")
    Map<String, Object> trashDocument(HttpServletRequest request, @PathVariable UUID documentId) {
        return documents.trash(AuthController.current(request).userId(), documentId);
    }

    @PostMapping("/api/v1/documents/{documentId}/restore")
    Map<String, Object> restore(HttpServletRequest request, @PathVariable UUID documentId) {
        return documents.restore(AuthController.current(request).userId(), documentId);
    }

    @DeleteMapping("/api/v1/documents/{documentId}/purge")
    ResponseEntity<Void> purge(HttpServletRequest request, @PathVariable UUID documentId) {
        documents.purgeNow(AuthController.current(request).userId(), documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/documents/{documentId}/star")
    ResponseEntity<Void> star(HttpServletRequest request, @PathVariable UUID documentId) {
        documents.star(AuthController.current(request).userId(), documentId, true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/documents/{documentId}/star")
    ResponseEntity<Void> unstar(HttpServletRequest request, @PathVariable UUID documentId) {
        documents.star(AuthController.current(request).userId(), documentId, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/documents/{documentId}/content-updates")
    Map<String, Object> append(HttpServletRequest request, @PathVariable UUID documentId, @RequestBody Map<String, Object> body) {
        var ack = documents.append(AuthController.current(request).userId(), documentId,
                UUID.fromString(String.valueOf(body.get("clientId"))),
                UUID.fromString(String.valueOf(body.get("updateId"))),
                ((Number) body.get("epoch")).intValue(),
                string(body.get("intent")),
                string(body.get("payloadBase64")));
        return Map.of("epoch", ack.epoch(), "seq", ack.seq(), "duplicate", ack.duplicate());
    }

    @GetMapping("/api/v1/documents/{documentId}/content-state")
    Map<String, Object> state(HttpServletRequest request, @PathVariable UUID documentId) {
        return documents.contentState(AuthController.current(request).userId(), documentId);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static UUID uuid(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return UUID.fromString(String.valueOf(value));
    }
}
