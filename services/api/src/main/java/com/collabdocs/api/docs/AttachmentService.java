package com.collabdocs.api.docs;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.DocumentRole;
import com.collabdocs.common.Ids;
import com.collabdocs.persistence.DocumentAccessService;
import com.collabdocs.persistence.ObjectStorage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentService {
    private final JdbcTemplate jdbc;
    private final DocumentAccessService access;
    private final ObjectStorage storage;

    public AttachmentService(JdbcTemplate jdbc, DocumentAccessService access, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.access = access;
        this.storage = storage;
    }

    @Transactional
    public Map<String, Object> upload(UUID actor, UUID documentId, byte[] bytes, String altText, boolean decorative) {
        access.require(actor, documentId, DocumentRole.EDITOR);
        if (bytes == null || bytes.length == 0 || bytes.length > 10 * 1024 * 1024) {
            throw new ApiException(400, "VALIDATION_FAILED", "Upload a JPEG, PNG, GIF, or WebP image.");
        }
        String mime = sniff(bytes);
        if (mime == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "Upload a JPEG, PNG, GIF, or WebP image.");
        }
        String alt = altText == null ? "" : altText.trim();
        if (!decorative && alt.isEmpty()) {
            throw new ApiException(400, "VALIDATION_FAILED", "Add alt text or mark the image as decorative.");
        }
        if (decorative) alt = "";
        UUID id = Ids.uuidV7();
        String key = "attachments/" + documentId + "/" + id;
        storage.put(key, bytes, mime);
        jdbc.update(
                """
                INSERT INTO attachments (id, document_id, object_key, mime_type, byte_size, alt_text, decorative, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, documentId, key, mime, bytes.length, alt, decorative, actor);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("mimeType", mime);
        body.put("byteSize", bytes.length);
        body.put("altText", alt);
        body.put("decorative", decorative);
        return body;
    }

    public Stored read(UUID actor, UUID documentId, UUID attachmentId) {
        access.require(actor, documentId, DocumentRole.VIEWER);
        var rows = jdbc.query(
                "SELECT object_key, mime_type FROM attachments WHERE id = ? AND document_id = ? AND deleted_at IS NULL",
                (rs, row) -> new Stored(rs.getString("mime_type"), storage.get(rs.getString("object_key"))),
                attachmentId, documentId);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Attachment is not available.");
        return rows.get(0);
    }

    @Transactional
    public void delete(UUID actor, UUID documentId, UUID attachmentId) {
        access.require(actor, documentId, DocumentRole.EDITOR);
        int updated = jdbc.update(
                "UPDATE attachments SET deleted_at = now() WHERE id = ? AND document_id = ? AND deleted_at IS NULL",
                attachmentId, documentId);
        if (updated == 0) throw new ApiException(404, "NOT_FOUND", "Attachment is not available.");
    }

    public static String sniff(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return MediaType.IMAGE_GIF_VALUE;
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    public record Stored(String mimeType, byte[] bytes) {}
}
