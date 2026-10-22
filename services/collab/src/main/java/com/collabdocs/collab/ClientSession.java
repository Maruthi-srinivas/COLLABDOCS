package com.collabdocs.collab;

import com.collabdocs.common.DocumentRole;
import java.util.UUID;

public record ClientSession(UUID connectionId, UUID userId, UUID sessionId, UUID documentId, UUID clientId,
                            int epoch, String intent, DocumentRole role, String displayName) {
    public boolean canEdit() {
        return "edit".equals(intent) && role.atLeast(DocumentRole.EDITOR);
    }

    public boolean allows(String updateIntent) {
        if ("suggest".equals(intent)) {
            return "suggest".equals(updateIntent) && role.atLeast(DocumentRole.COMMENTER);
        }
        return "edit".equals(intent) && "edit".equals(updateIntent) && role.atLeast(DocumentRole.EDITOR);
    }
}
