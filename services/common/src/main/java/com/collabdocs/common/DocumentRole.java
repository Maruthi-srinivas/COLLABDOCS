package com.collabdocs.common;

public enum DocumentRole {
    VIEWER(1),
    COMMENTER(2),
    EDITOR(3),
    OWNER(4);

    private final int rank;

    DocumentRole(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(DocumentRole minimum) {
        return rank >= minimum.rank;
    }

    public static DocumentRole parse(String value) {
        return DocumentRole.valueOf(value.toUpperCase());
    }
}
