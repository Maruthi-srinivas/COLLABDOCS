package com.collabdocs.api.web;

import com.collabdocs.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException exception) {
        return body(exception.status(), exception.code(), exception.getMessage(), exception.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException exception) {
        var details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "issue", "invalid")).toList();
        return body(400, "VALIDATION_FAILED", "Check the submitted fields.", details);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    ResponseEntity<Map<String, Object>> missing(NoHandlerFoundException exception) {
        return body(404, "NOT_FOUND", "Not found.", java.util.List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception exception) {
        return body(500, "INTERNAL", "Something went wrong.", java.util.List.of());
    }

    private static ResponseEntity<Map<String, Object>> body(int status, String code, String message, Object details) {
        String correlation = MDC.get("correlationId");
        return ResponseEntity.status(status).body(Map.of("error", Map.of(
                "code", code,
                "message", message,
                "details", details,
                "correlationId", correlation == null ? "" : correlation)));
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
