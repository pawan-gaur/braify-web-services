package com.braify.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.Instant;
import java.util.Map;

/**
 * Converts any unhandled exception into a consistent JSON error body:
 * <pre>
 * {
 *   "status":  404,
 *   "message": "Email template not found: abc123",
 *   "timestamp": "2026-04-29T10:23:45Z"
 * }
 * </pre>
 * This lets the React front-end display the real reason instead of a
 * generic "backend not running" fallback.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Access denied — thrown by Spring Security @PreAuthorize. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorBody(HttpStatus.FORBIDDEN, "Access denied"));
    }

    /** Resource not found — RuntimeException thrown by services. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex) {
        String msg = ex.getMessage();
        HttpStatus status = (msg != null && msg.toLowerCase().contains("not found"))
                ? HttpStatus.NOT_FOUND
                : HttpStatus.INTERNAL_SERVER_ERROR;

        return ResponseEntity.status(status).body(errorBody(status, msg));
    }

    /** 404 when Spring MVC finds no matching route (needs spring.mvc.throw-exception-if-no-handler-found=true). */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoRoute(NoHandlerFoundException ex) {
        String msg = "No endpoint: " + ex.getHttpMethod() + " " + ex.getRequestURL();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(HttpStatus.NOT_FOUND, msg));
    }

    /** Catch-all for anything else. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR,
                        ex.getMessage() != null ? ex.getMessage() : "Unexpected error"));
    }

    private Map<String, Object> errorBody(HttpStatus status, String message) {
        return Map.of(
                "status",    status.value(),
                "message",   message != null ? message : status.getReasonPhrase(),
                "timestamp", Instant.now().toString()
        );
    }
}
