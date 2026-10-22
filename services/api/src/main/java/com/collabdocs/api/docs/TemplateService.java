package com.collabdocs.api.docs;

import com.collabdocs.persistence.DocumentAccessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TemplateService {
    private final JdbcTemplate jdbc;
    private final DocumentAccessService access;

    public TemplateService(JdbcTemplate jdbc, DocumentAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public Map<String, Object> list(UUID userId, UUID workspaceId) {
        if (workspaceId != null) access.requireWorkspaceMember(userId, workspaceId);
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT id, name, description, category FROM templates
                WHERE workspace_id IS NULL OR workspace_id = ?
                ORDER BY name
                """,
                (rs, row) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", rs.getObject("id", UUID.class));
                    body.put("name", rs.getString("name"));
                    body.put("description", rs.getString("description"));
                    body.put("category", rs.getString("category"));
                    return body;
                }, workspaceId);
        return Map.of("items", items);
    }
}
