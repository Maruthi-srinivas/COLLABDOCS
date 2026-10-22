package com.collabdocs.api.docs;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.DocumentRole;
import com.collabdocs.common.Ids;
import com.collabdocs.persistence.DocumentAccessService;
import com.collabdocs.persistence.ObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
    private static final Set<String> FORMATS = Set.of("pdf", "docx", "html", "txt");
    private final JdbcTemplate jdbc;
    private final DocumentAccessService access;
    private final ObjectStorage storage;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public TransferService(JdbcTemplate jdbc, DocumentAccessService access, ObjectStorage storage,
                           StringRedisTemplate redis, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.storage = storage;
        this.redis = redis;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> createExport(UUID actor, UUID documentId, String format, boolean includeSuggestions, String idempotencyKey) {
        access.require(actor, documentId, DocumentRole.VIEWER);
        if (format == null || !FORMATS.contains(format)) {
            throw new ApiException(400, "VALIDATION_FAILED", "Choose pdf, docx, html, or txt.");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > 100) {
            throw new ApiException(400, "VALIDATION_FAILED", "Idempotency key is too long.");
        }
        if (key != null) {
            List<Map<String, Object>> existing = jdbc.query(
                    "SELECT id, document_id, format, status, error_code FROM export_jobs WHERE requested_by = ? AND idempotency_key = ?",
                    (rs, row) -> exportRow(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                            rs.getString("format"), rs.getString("status"), rs.getString("error_code"), null),
                    actor, key);
            if (!existing.isEmpty()) return existing.get(0);
        }
        UUID id = Ids.uuidV7();
        jdbc.update(
                """
                INSERT INTO export_jobs (id, document_id, requested_by, format, include_suggestions, status, idempotency_key)
                VALUES (?, ?, ?, ?, ?, 'queued', ?)
                """,
                id, documentId, actor, format, includeSuggestions, key);
        publish("jobs:export", id);
        return exportRow(id, documentId, format, "queued", null, null);
    }

    public Map<String, Object> getExport(UUID actor, UUID jobId) {
        return requireExport(actor, jobId);
    }

    public Download download(UUID actor, UUID jobId) {
        Map<String, Object> job = exportRecord(jobId);
        try {
            access.require(actor, (UUID) job.get("documentId"), DocumentRole.VIEWER);
        } catch (ApiException exception) {
            throw new ApiException(403, "FORBIDDEN", "You do not have permission for this export.");
        }
        if (!"succeeded".equals(job.get("status"))) {
            throw new ApiException(409, "EXPORT_NOT_READY", "The export is not ready.");
        }
        String key = jdbc.queryForObject("SELECT object_key FROM export_jobs WHERE id = ?", String.class, jobId);
        String format = String.valueOf(job.get("format"));
        return new Download(storage.get(key), mediaType(format), "document." + ("txt".equals(format) ? "txt" : format));
    }

    @Transactional
    public Map<String, Object> createImport(UUID actor, UUID workspaceId, UUID folderId, String format, byte[] file) {
        access.requireWorkspaceMember(actor, workspaceId);
        if (file == null || file.length == 0 || file.length > 20 * 1024 * 1024) {
            throw new ApiException(400, "VALIDATION_FAILED", "Import a DOCX or HTML file up to 20 MB.");
        }
        if (!"docx".equals(format) && !"html".equals(format)) {
            throw new ApiException(400, "VALIDATION_FAILED", "Import a DOCX or HTML file up to 20 MB.");
        }
        if (folderId != null) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM folders WHERE id = ? AND workspace_id = ? AND status = 'active'",
                    Integer.class, folderId, workspaceId);
            if (count == null || count == 0) throw new ApiException(404, "NOT_FOUND", "Folder is not available.");
        }
        UUID id = Ids.uuidV7();
        String objectKey = "imports/" + id;
        storage.put(objectKey, file, "docx".equals(format)
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : MediaType.TEXT_HTML_VALUE);
        jdbc.update(
                """
                INSERT INTO import_jobs (id, workspace_id, requested_by, folder_id, source_format, source_object_key, status)
                VALUES (?, ?, ?, ?, ?, ?, 'queued')
                """,
                id, workspaceId, actor, folderId, format, objectKey);
        publish("jobs:import", id);
        return exportRow(id, null, format, "queued", null, null);
    }

    public Map<String, Object> getImport(UUID actor, UUID jobId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, document_id, source_format, status, error_code, report::text, requested_by FROM import_jobs WHERE id = ?",
                (rs, row) -> {
                    if (!actor.equals(rs.getObject("requested_by", UUID.class))) {
                        throw new ApiException(404, "NOT_FOUND", "Import is not available.");
                    }
                    Object report = null;
                    try {
                        String raw = rs.getString("report");
                        if (raw != null) report = mapper.readValue(raw, Map.class);
                    } catch (Exception ignored) {
                        report = null;
                    }
                    return exportRow(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                            rs.getString("source_format"), rs.getString("status"), rs.getString("error_code"), report);
                }, jobId);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Import is not available.");
        return rows.get(0);
    }

    private Map<String, Object> requireExport(UUID actor, UUID jobId) {
        Map<String, Object> job = exportRecord(jobId);
        access.require(actor, (UUID) job.get("documentId"), DocumentRole.VIEWER);
        return job;
    }

    private Map<String, Object> exportRecord(UUID jobId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, document_id, format, status, error_code FROM export_jobs WHERE id = ?",
                (rs, row) -> exportRow(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("format"), rs.getString("status"), rs.getString("error_code"), null),
                jobId);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Export is not available.");
        return rows.get(0);
    }

    private void publish(String stream, UUID jobId) {
        try {
            MapRecord<String, String, String> record = StreamRecords.string(Map.of("jobId", jobId.toString())).withStreamKey(stream);
            redis.opsForStream().add(record);
        } catch (RuntimeException ignored) {
            // The worker also polls queued rows.
        }
    }

    private static Map<String, Object> exportRow(UUID id, UUID documentId, String format, String status, String errorCode, Object report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("documentId", documentId);
        body.put("format", format);
        body.put("status", status);
        body.put("errorCode", errorCode);
        body.put("report", report);
        return body;
    }

    private static String mediaType(String format) {
        return switch (format) {
            case "pdf" -> MediaType.APPLICATION_PDF_VALUE;
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "html" -> MediaType.TEXT_HTML_VALUE;
            default -> MediaType.TEXT_PLAIN_VALUE;
        };
    }

    public record Download(byte[] bytes, String mediaType, String filename) {}
}
