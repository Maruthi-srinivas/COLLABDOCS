package com.collabdocs.api.docs;

import com.collabdocs.persistence.ObjectStorage;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class TemplateSeed {
    private static final Logger log = LoggerFactory.getLogger(TemplateSeed.class);
    private static final String[][] FILES = {
            {"templates/blank.bin", "collab/empty-doc-v1.bin"},
            {"templates/letter.bin", "collab/templates/letter.bin"},
            {"templates/meeting-notes.bin", "collab/templates/meeting-notes.bin"},
            {"templates/project-brief.bin", "collab/templates/project-brief.bin"},
    };
    private final ObjectStorage storage;

    public TemplateSeed(ObjectStorage storage) {
        this.storage = storage;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void uploadMissing() {
        for (String[] pair : FILES) {
            try {
                storage.get(pair[0]);
            } catch (RuntimeException missing) {
                try {
                    byte[] bytes = new ClassPathResource(pair[1]).getContentAsByteArray();
                    storage.put(pair[0], bytes, "application/octet-stream");
                } catch (IOException exception) {
                    log.warn("Template snapshot {} is not packaged", pair[1]);
                }
            }
        }
    }
}
