package com.collabdocs.api.docs;

import com.collabdocs.common.ApiException;
import com.collabdocs.common.DocumentRole;
import com.collabdocs.common.Ids;
import com.collabdocs.persistence.DocumentAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class VersionService {
    private final JdbcTemplate jdbc;
    private final DocumentAccessService access;
    private final CompactorClient compactor;
    private final DocEvents events;
    private final ObjectMapper mapper;

    public VersionService(JdbcTemplate jdbc, DocumentAccessService access, CompactorClient compactor,
                          DocEvents events, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.compactor = compactor;
        this.events = events;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> list(UUID actor, UUID documentId) {
        access.require(actor, documentId, DocumentRole.VIEWER);
        return jdbc.query(
                """
                SELECT v.id, v.kind, v.label, v.created_at, v.created_by, u.display_name
                FROM document_versions v
                LEFT JOIN users u ON u.id = v.created_by
                WHERE v.document_id = ?
                  AND (
                    v.kind = 'named'
                    OR v.created_at >= now() - interval '90 days'
                    OR v.id IN (
                      SELECT id FROM document_versions
                      WHERE document_id = ? AND kind = 'automatic'
                      ORDER BY created_at DESC LIMIT 100
                    )
                  )
                ORDER BY v.created_at DESC
                """,
                (rs, row) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", rs.getObject("id", UUID.class));
                    body.put("kind", rs.getString("kind"));
                    body.put("label", rs.getString("label"));
                    body.put("createdBy", rs.getObject("created_by", UUID.class));
                    body.put("authorName", rs.getString("display_name"));
                    body.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return body;
                }, documentId, documentId);
    }

    public Map<String, Object> name(UUID actor, UUID documentId, String label) {
        access.require(actor, documentId, DocumentRole.EDITOR);
        if (label == null || label.isBlank() || label.length() > 120) {
            throw new ApiException(400, "VALIDATION_FAILED", "Name the version with 1 to 120 characters.");
        }
        UUID requestId = Ids.uuidV7();
        jdbc.update(
                """
                INSERT INTO snapshot_requests (id, document_id, requested_by, kind, label, status)
                VALUES (?, ?, ?, 'named', ?, 'pending')
                """,
                requestId, documentId, actor, label.trim());
        try {
            JsonNode result = compactor.compact(documentId.toString(), requestId.toString());
            if (result.path("versionId").isMissingNode() || result.path("versionId").asText().isBlank()) {
                return Map.of("ready", false, "id", requestId, "status", "pending");
            }
            return Map.of("ready", true, "id", UUID.fromString(result.path("versionId").asText()), "label", label.trim(), "kind", "named");
        } catch (Exception exception) {
            return Map.of("ready", false, "id", requestId, "status", "pending");
        }
    }

    @Transactional
    public void deleteNamed(UUID actor, UUID documentId, UUID versionId) {
        access.require(actor, documentId, DocumentRole.EDITOR);
        int deleted = jdbc.update(
                "DELETE FROM document_versions WHERE id = ? AND document_id = ? AND kind = 'named'",
                versionId, documentId);
        if (deleted == 0) throw new ApiException(404, "NOT_FOUND", "Named version is not available.");
    }

    public String preview(UUID actor, UUID documentId, UUID versionId) throws Exception {
        access.require(actor, documentId, DocumentRole.VIEWER);
        JsonNode projected = compactor.project(snapshot(documentId, versionId));
        return projected.path("acceptedHtml").asText("<p></p>");
    }

    public Map<String, Object> compare(UUID actor, UUID documentId, UUID fromId, UUID toId) throws Exception {
        access.require(actor, documentId, DocumentRole.VIEWER);
        List<String> from = paragraphs(compactor.project(snapshot(documentId, fromId)).path("acceptedText").asText(""));
        List<String> to = paragraphs(compactor.project(snapshot(documentId, toId)).path("acceptedText").asText(""));
        return Map.of("from", fromId, "to", toId, "changes", diff(from, to));
    }

    @Transactional
    public Map<String, Object> restore(UUID actor, UUID documentId, UUID versionId, boolean confirm) {
        if (!confirm) throw new ApiException(400, "VALIDATION_FAILED", "Confirm the restore before replacing the document.");
        access.require(actor, documentId, DocumentRole.EDITOR);
        var locked = jdbc.query(
                "SELECT epoch, schema_version, workspace_id FROM documents WHERE id = ? FOR UPDATE",
                (rs, row) -> new int[] {rs.getInt("epoch"), rs.getInt("schema_version"), 0}, documentId);
        if (locked.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Document is not available.");
        int nextEpoch = locked.get(0)[0] + 1;
        int schemaVersion = locked.get(0)[1];
        UUID workspaceId = jdbc.queryForObject("SELECT workspace_id FROM documents WHERE id = ?", UUID.class, documentId);
        byte[] state = snapshot(documentId, versionId);
        jdbc.update("UPDATE documents SET epoch = ?, updated_at = now() WHERE id = ?", nextEpoch, documentId);
        jdbc.update(
                "INSERT INTO document_epochs (document_id, epoch, last_seq, snapshot_through_seq) VALUES (?, ?, 0, 0)",
                documentId, nextEpoch);
        jdbc.update(
                """
                INSERT INTO document_snapshots
                  (id, document_id, epoch, through_seq, schema_version, kind, byte_size, state, created_by)
                VALUES (?, ?, ?, 0, ?, 'restore', ?, ?, ?)
                """,
                Ids.uuidV7(), documentId, nextEpoch, schemaVersion, state.length, state, actor);
        try {
            jdbc.update(
                    """
                    INSERT INTO audit_events (workspace_id, actor_id, action, target_type, target_id, metadata)
                    VALUES (?, ?, 'restore', 'document', ?, ?::jsonb)
                    """,
                    workspaceId, actor, documentId, mapper.writeValueAsString(Map.of("versionId", versionId.toString(), "epoch", nextEpoch)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
        afterCommit(() -> events.restored(documentId, nextEpoch, state, 0));
        return Map.of("epoch", nextEpoch, "versionId", versionId);
    }

    private byte[] snapshot(UUID documentId, UUID versionId) {
        List<byte[]> rows = jdbc.query(
                """
                SELECT s.state FROM document_versions v
                JOIN document_snapshots s ON s.id = v.snapshot_id
                WHERE v.id = ? AND v.document_id = ? AND s.state IS NOT NULL
                """,
                (rs, row) -> rs.getBytes("state"), versionId, documentId);
        if (rows.isEmpty() || rows.get(0) == null) throw new ApiException(404, "NOT_FOUND", "Version is not available.");
        return rows.get(0);
    }

    private static List<String> paragraphs(String text) {
        if (text == null || text.isBlank()) return List.of();
        return List.of(text.split("\\n", -1));
    }

    static List<Map<String, String>> diff(List<String> from, List<String> to) {
        int n = from.size();
        int m = to.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i -= 1) {
            for (int j = m - 1; j >= 0; j -= 1) {
                lcs[i][j] = from.get(i).equals(to.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Map<String, String>> changes = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (from.get(i).equals(to.get(j))) {
                changes.add(Map.of("op", "equal", "text", from.get(i)));
                i += 1;
                j += 1;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                changes.add(Map.of("op", "delete", "text", from.get(i)));
                i += 1;
            } else {
                changes.add(Map.of("op", "insert", "text", to.get(j)));
                j += 1;
            }
        }
        while (i < n) {
            changes.add(Map.of("op", "delete", "text", from.get(i)));
            i += 1;
        }
        while (j < m) {
            changes.add(Map.of("op", "insert", "text", to.get(j)));
            j += 1;
        }
        return changes;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
