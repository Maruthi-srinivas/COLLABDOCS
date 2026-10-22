package com.collabdocs.api.web;

import com.collabdocs.api.docs.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class TransferController {
    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping("/api/v1/documents/{documentId}/exports")
    ResponseEntity<Map<String, Object>> createExport(HttpServletRequest request, @PathVariable UUID documentId,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                      @RequestBody Map<String, Object> body) {
        boolean suggestions = Boolean.TRUE.equals(body.get("includeSuggestions"));
        Map<String, Object> job = transfers.createExport(AuthController.current(request).userId(), documentId,
                body.get("format") == null ? null : String.valueOf(body.get("format")), suggestions, idempotencyKey);
        HttpStatus status = "queued".equals(job.get("status")) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(job);
    }

    @GetMapping("/api/v1/exports/{jobId}")
    Map<String, Object> export(HttpServletRequest request, @PathVariable UUID jobId) {
        return transfers.getExport(AuthController.current(request).userId(), jobId);
    }

    @GetMapping("/api/v1/exports/{jobId}/download")
    ResponseEntity<byte[]> download(HttpServletRequest request, @PathVariable UUID jobId) {
        TransferService.Download file = transfers.download(AuthController.current(request).userId(), jobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .body(file.bytes());
    }

    @PostMapping(value = "/api/v1/workspaces/{workspaceId}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String, Object>> createImport(HttpServletRequest request, @PathVariable UUID workspaceId,
                                                      @RequestParam("file") MultipartFile file,
                                                      @RequestParam String format,
                                                      @RequestParam(required = false) UUID folderId) throws IOException {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(transfers.createImport(
                AuthController.current(request).userId(), workspaceId, folderId, format, file.getBytes()));
    }

    @GetMapping("/api/v1/imports/{jobId}")
    Map<String, Object> importJob(HttpServletRequest request, @PathVariable UUID jobId) {
        return transfers.getImport(AuthController.current(request).userId(), jobId);
    }
}
