package com.collabdocs.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocumentRoleTest {
    @Test
    void ownerOutranksEditor() {
        assertTrue(DocumentRole.OWNER.atLeast(DocumentRole.EDITOR));
        assertFalse(DocumentRole.VIEWER.atLeast(DocumentRole.EDITOR));
    }
}
