package com.collabdocs.api.web;

import com.collabdocs.api.docs.AttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AttachmentController {
    private final AttachmentService attachments;

    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @PostMapping(value = "/api/v1/documents/{documentId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> upload(HttpServletRequest request, @PathVariable UUID documentId,
                             @RequestParam("file") MultipartFile file,
                             @RequestParam(required = false) String altText,
                             @RequestParam(defaultValue = "false") boolean decorative) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(attachments.upload(
                AuthController.current(request).userId(), documentId, file.getBytes(), altText, decorative));
    }

    @GetMapping("/api/v1/documents/{documentId}/attachments/{attachmentId}")
    ResponseEntity<byte[]> read(HttpServletRequest request, @PathVariable UUID documentId, @PathVariable UUID attachmentId) {
        AttachmentService.Stored stored = attachments.read(AuthController.current(request).userId(), documentId, attachmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, stored.mimeType())
                .body(stored.bytes());
    }

    @DeleteMapping("/api/v1/documents/{documentId}/attachments/{attachmentId}")
    ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable UUID documentId, @PathVariable UUID attachmentId) {
        attachments.delete(AuthController.current(request).userId(), documentId, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
