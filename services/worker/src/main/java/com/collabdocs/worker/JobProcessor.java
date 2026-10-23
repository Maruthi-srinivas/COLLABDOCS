package com.collabdocs.worker;

import com.collabdocs.common.Ids;
import com.collabdocs.persistence.ObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobProcessor {
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final StringRedisTemplate redis;
    private final CompactorClient compactor;
    private final DocumentConverter converter;
    private final ObjectMapper mapper;
    private final ClamAvScanner scanner;
    private final String workerName;

    public JobProcessor(JdbcTemplate jdbc, ObjectStorage storage, StringRedisTemplate redis, CompactorClient compactor,
                        DocumentConverter converter, ObjectMapper mapper, ClamAvScanner scanner,
                        @Value("${collabdocs.worker-name:worker-1}") String workerName) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.redis = redis;
        this.compactor = compactor;
        this.converter = converter;
        this.mapper = mapper;
        this.scanner = scanner;
        this.workerName = workerName;
        ensureGroup("jobs:export");
        ensureGroup("jobs:import");
    }

    @Scheduled(fixedDelay = 1000)
    public void drain() {
        read("jobs:export", true);
        read("jobs:import", false);
        jdbc.query("SELECT id FROM export_jobs WHERE status = 'queued' ORDER BY created_at LIMIT 5",
                (rs, row) -> rs.getObject("id", UUID.class)).forEach(this::exportJob);
        jdbc.query("SELECT id FROM import_jobs WHERE status = 'queued' ORDER BY created_at LIMIT 5",
                (rs, row) -> rs.getObject("id", UUID.class)).forEach(this::importJob);
    }

    private void read(String stream, boolean export) {
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    org.springframework.data.redis.connection.stream.Consumer.from("workers", workerName),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(5),
                    StreamOffset.create(stream, ReadOffset.lastConsumed()));
            if (records == null) return;
            for (MapRecord<String, Object, Object> record : records) {
                Object id = record.getValue().get("jobId");
                if (id != null) {
                    if (export) exportJob(UUID.fromString(id.toString()));
                    else importJob(UUID.fromString(id.toString()));
                }
                redis.opsForStream().acknowledge(stream, "workers", record.getId());
            }
        } catch (RuntimeException ignored) {
            // The database poll still claims queued jobs.
        }
    }

    private void ensureGroup(String stream) {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), "workers");
        } catch (RuntimeException ignored) {
            // The group already exists.
        }
    }

    public void exportJob(UUID jobId) {
        int claimed = jdbc.update(
                "UPDATE export_jobs SET status = 'running', started_at = now(), attempts = attempts + 1 WHERE id = ? AND status = 'queued'",
                jobId);
        if (claimed == 0) return;
        long started = System.nanoTime();
        try {
            var rows = jdbc.query(
                    """
                    SELECT document_id, requested_by, format, include_suggestions,
                           (SELECT header_text FROM documents d WHERE d.id = export_jobs.document_id) AS header_text,
                           (SELECT footer_text FROM documents d WHERE d.id = export_jobs.document_id) AS footer_text,
                           (SELECT page_numbers FROM documents d WHERE d.id = export_jobs.document_id) AS page_numbers
                    FROM export_jobs WHERE id = ?
                    """,
                    (rs, row) -> new ExportRow(rs.getObject("document_id", UUID.class), rs.getObject("requested_by", UUID.class),
                            rs.getString("format"), rs.getBoolean("include_suggestions"), rs.getString("header_text"),
                            rs.getString("footer_text"), rs.getBoolean("page_numbers")),
                    jobId);
            ExportRow job = rows.get(0);
            int epoch = jdbc.queryForObject("SELECT epoch FROM documents WHERE id = ?", Integer.class, job.documentId);
            List<SnapshotTail> snapshots = jdbc.query(
                    """
                    SELECT through_seq, state FROM document_snapshots
                    WHERE document_id = ? AND epoch = ? AND state IS NOT NULL
                    ORDER BY through_seq DESC LIMIT 1
                    """,
                    (rs, row) -> new SnapshotTail(rs.getLong("through_seq"), rs.getBytes("state")),
                    job.documentId, epoch);
            long through = snapshots.isEmpty() ? 0 : snapshots.get(0).through();
            byte[] snapshot = snapshots.isEmpty() ? new byte[0] : snapshots.get(0).state();
            List<byte[]> updates = jdbc.query(
                    "SELECT payload FROM collab_updates WHERE document_id = ? AND epoch = ? AND seq > ? ORDER BY seq",
                    (rs, row) -> rs.getBytes("payload"), job.documentId, epoch, through);
            JsonNode projected = compactor.project(snapshot, updates);
            String html = job.includeSuggestions ? projected.path("markedHtml").asText() : projected.path("acceptedHtml").asText();
            Map<String, byte[]> images = new HashMap<>();
            Map<String, String> types = new HashMap<>();
            jdbc.query("SELECT id, object_key, mime_type FROM attachments WHERE document_id = ? AND deleted_at IS NULL",
                    (rs, row) -> {
                        String id = rs.getObject("id", UUID.class).toString();
                        images.put(id, storage.get(rs.getString("object_key")));
                        types.put(id, rs.getString("mime_type"));
                        return null;
                    }, job.documentId);
            String embedded = converter.embedImages(html, images, types);
            byte[] body = switch (job.format) {
                case "pdf" -> converter.pdf(embedded, job.header, job.footer, job.pageNumbers);
                case "docx" -> converter.docx(embedded, job.header, job.footer, job.pageNumbers, images);
                case "html" -> converter.html(embedded);
                default -> converter.text(projected.path("acceptedText").asText());
            };
            String key = "exports/" + jobId;
            storage.put(key, body, "application/octet-stream");
            jdbc.update("UPDATE export_jobs SET status = 'succeeded', object_key = ?, finished_at = now() WHERE id = ?", key, jobId);
            jdbc.update(
                    "INSERT INTO notifications (id, user_id, type, payload) VALUES (?, ?, 'export_ready', ?::jsonb)",
                    Ids.uuidV7(), job.requestedBy, mapper.writeValueAsString(Map.of("jobId", jobId, "documentId", job.documentId, "format", job.format)));
            com.collabdocs.common.AppMetrics.INSTANCE.observe("export_job_duration_seconds", (System.nanoTime() - started) / 1_000_000_000d,
                    "format", job.format, "result", "succeeded");
        } catch (Exception exception) {
            com.collabdocs.common.AppMetrics.INSTANCE.observe("export_job_duration_seconds", (System.nanoTime() - started) / 1_000_000_000d,
                    "format", "unknown", "result", "failed");
            fail("export_jobs", jobId, "EXPORT_FAILED");
        }
    }

    public void importJob(UUID jobId) {
        int claimed = jdbc.update(
                "UPDATE import_jobs SET status = 'running', attempts = attempts + 1 WHERE id = ? AND status = 'queued'",
                jobId);
        if (claimed == 0) return;
        try {
            var rows = jdbc.query(
                    "SELECT workspace_id, requested_by, folder_id, source_format, source_object_key FROM import_jobs WHERE id = ?",
                    (rs, row) -> new ImportRow(rs.getObject("workspace_id", UUID.class), rs.getObject("requested_by", UUID.class),
                            rs.getObject("folder_id", UUID.class), rs.getString("source_format"), rs.getString("source_object_key")),
                    jobId);
            ImportRow job = rows.get(0);
            byte[] source = storage.get(job.objectKey);
            try {
                scanner.scan(source);
            } catch (ClamAvScanner.MalwareDetected detected) {
                try {
                    storage.delete(job.objectKey);
                } catch (RuntimeException ignored) {
                    // The job is still failed and the object key is not attached to a document.
                }
                failNow("import_jobs", jobId, "MALWARE_DETECTED");
                return;
            }
            DocumentConverter.Imported imported = "docx".equals(job.format)
                    ? converter.importDocx(source)
                    : converter.importHtml(new String(source, java.nio.charset.StandardCharsets.UTF_8));
            if (imported.images().size() > 2000) throw new IllegalStateException("too many images");
            byte[] snapshot = compactor.encode(imported.document());
            UUID documentId = Ids.uuidV7();
            jdbc.update(
                    "INSERT INTO documents (id, workspace_id, folder_id, owner_id, title, schema_version) VALUES (?, ?, ?, ?, ?, 2)",
                    documentId, job.workspaceId, job.folderId, job.requestedBy, "Imported document");
            jdbc.update("INSERT INTO document_epochs (document_id, epoch) VALUES (?, 1)", documentId);
            jdbc.update(
                    """
                    INSERT INTO document_snapshots
                      (id, document_id, epoch, through_seq, schema_version, kind, byte_size, state, created_by)
                    VALUES (?, ?, 1, 0, 2, 'import', ?, ?, ?)
                    """,
                    Ids.uuidV7(), documentId, snapshot.length, snapshot, job.requestedBy);
            jdbc.update(
                    "INSERT INTO document_permissions (id, document_id, principal_type, user_id, role, granted_by) VALUES (?, ?, 'user', ?, 'owner', ?)",
                    Ids.uuidV7(), documentId, job.requestedBy, job.requestedBy);
            for (DocumentConverter.StoredImage image : imported.images()) {
                String key = "attachments/" + documentId + "/" + image.id();
                storage.put(key, image.bytes(), image.mime());
                jdbc.update(
                        """
                        INSERT INTO attachments (id, document_id, object_key, mime_type, byte_size, alt_text, decorative, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, false, ?)
                        """,
                        image.id(), documentId, key, image.mime(), image.bytes().length, image.alt(), job.requestedBy);
            }
            jdbc.update(
                    "UPDATE import_jobs SET status = 'succeeded', document_id = ?, report = ?::jsonb, finished_at = now() WHERE id = ?",
                    documentId, mapper.writeValueAsString(Map.of("dropped", imported.dropped())), jobId);
        } catch (Exception exception) {
            fail("import_jobs", jobId, "IMPORT_FAILED");
        }
    }

    private void failNow(String table, UUID jobId, String code) {
        jdbc.update("UPDATE " + table + " SET status = 'failed', error_code = ?, finished_at = now() WHERE id = ?", code, jobId);
    }

    private void fail(String table, UUID jobId, String code) {
        Integer attempts = jdbc.queryForObject("SELECT attempts FROM " + table + " WHERE id = ?", Integer.class, jobId);
        String status = attempts != null && attempts >= 3 ? "failed" : "queued";
        jdbc.update("UPDATE " + table + " SET status = ?, error_code = ?, finished_at = CASE WHEN ? = 'failed' THEN now() ELSE NULL END WHERE id = ?",
                status, code, status, jobId);
    }

    private record SnapshotTail(long through, byte[] state) {}

    private record ExportRow(UUID documentId, UUID requestedBy, String format, boolean includeSuggestions,
                             String header, String footer, boolean pageNumbers) {}

    private record ImportRow(UUID workspaceId, UUID requestedBy, UUID folderId, String format, String objectKey) {}
}
