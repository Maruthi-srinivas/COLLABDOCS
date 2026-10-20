package com.collabdocs.common;

import java.util.List;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<FieldDetail> details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(int status, String code, String message, List<FieldDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldDetail> details() {
        return details;
    }

    public record FieldDetail(String field, String issue) {}
}
