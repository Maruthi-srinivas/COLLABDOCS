package com.collabdocs.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentPurgeService {
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;

    public DocumentPurgeService(JdbcTemplate jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @Transactional
    public void purge(UUID documentId) {
        List<String> keys = jdbc.query("SELECT object_key FROM attachments WHERE document_id = ?",
                (rs, row) -> rs.getString(1), documentId);
        jdbc.update("DELETE FROM comments WHERE thread_id IN (SELECT id FROM comment_threads WHERE document_id = ?)", documentId);
        jdbc.update("DELETE FROM comment_threads WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM suggestions WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM collab_updates WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document_versions WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM snapshot_requests WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document_snapshots WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document_epochs WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document_permissions WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM share_links WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM access_requests WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM export_jobs WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM import_jobs WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM attachments WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document_stars WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM recent_documents WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        keys.forEach(storage::delete);
    }
}
