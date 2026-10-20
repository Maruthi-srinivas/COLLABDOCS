package com.collabdocs.collab;

import com.collabdocs.common.DocumentRole;
import java.util.UUID;

public record ClientSession(UUID connectionId, UUID userId, UUID sessionId, UUID documentId, UUID clientId,
                            int epoch, String intent, DocumentRole role, String displayName) {
    public boolean canEdit() {
        return "edit".equals(intent) && role.atLeast(DocumentRole.EDITOR);
    }
}
