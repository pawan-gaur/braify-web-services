package com.braify.shared;

import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.esign.exception.SigningLinkException;
import com.braify.feature.quota.exception.QuotaExceededException;
import com.braify.security.UserDetailsImpl;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

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
 *
 * <p>Logging policy:
 * <ul>
 *   <li>5xx errors (RuntimeException without "not found", generic Exception) → {@code log.error} with stack trace</li>
 *   <li>4xx errors (quota, access denied, not found) → {@code log.warn} without stack trace</li>
 *   <li>NoHandlerFoundException → {@code log.debug} (normal miss on unknown routes)</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final AuditLogService auditLogService;

    /** Bean Validation failure on @RequestBody — returns field-level errors. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                        (a, b) -> a,          // keep first error per field
                        LinkedHashMap::new
                ));
        log.warn("Validation failed: {}", fieldErrors);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",    400);
        body.put("message",   "Validation failed");
        body.put("errors",    fieldErrors);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.badRequest().body(body);
    }

    /** @RequestParam / @PathVariable constraint violations (requires @Validated on controller). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        cv -> cv.getPropertyPath().toString(),
                        cv -> cv.getMessage(),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
        log.warn("Constraint violation: {}", fieldErrors);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",    400);
        body.put("message",   "Constraint violation");
        body.put("errors",    fieldErrors);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.badRequest().body(body);
    }

    /** Bad caller argument — e.g. invalid enum value or explicit guard. Returns HTTP 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorBody(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    /** Quota exceeded — org has hit a usage limit. Returns HTTP 429. */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceeded(QuotaExceededException ex) {
        log.warn("Quota exceeded: type={} limit={} current={}", ex.getQuotaType(), ex.getLimit(), ex.getCurrent());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                        "status",    429,
                        "message",   ex.getMessage(),
                        "quotaType", ex.getQuotaType(),
                        "limit",     ex.getLimit(),
                        "current",   ex.getCurrent(),
                        "timestamp", Instant.now().toString()
                ));
    }

    /** Access denied — thrown by Spring Security @PreAuthorize. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        auditAccessDenied();
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorBody(HttpStatus.FORBIDDEN, "Access denied"));
    }

    /** Records an authenticated user's denied (403) attempt for the compliance trail. */
    private void auditAccessDenied() {
        try {
            var authn = SecurityContextHolder.getContext().getAuthentication();
            if (authn == null || !(authn.getPrincipal() instanceof UserDetailsImpl ud)) return; // anonymous → skip
            String path = "request";
            try {
                var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attrs != null) {
                    path = attrs.getRequest().getMethod() + " " + attrs.getRequest().getRequestURI();
                }
            } catch (Exception ignored) { /* no request context */ }
            auditLogService.logFailureByUser(path, "Access denied",
                    AuditLog.Action.ACCESS_DENIED, AuditLog.ResourceType.SESSION,
                    ud.getAppUser(), "Denied: " + path);
        } catch (Exception e) {
            log.debug("Failed to audit access-denied event: {}", e.getMessage());
        }
    }

    /**
     * Business-rule conflict — e.g. "cannot resend a COMPLETED document",
     * "job already in terminal state".  Returns HTTP 409 Conflict.
     *
     * <p>Must be declared before the {@link RuntimeException} handler because
     * {@link IllegalStateException} is a subclass of {@link RuntimeException};
     * Spring picks the most specific handler first.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("Illegal state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorBody(HttpStatus.CONFLICT, ex.getMessage()));
    }

    /**
     * Signing-link open failure — returns a classified {@code reason} so the signing
     * page can show a specific message. Uses non-401 statuses so the frontend's
     * silent-refresh flow is never triggered on this public page.
     */
    @ExceptionHandler(SigningLinkException.class)
    public ResponseEntity<Map<String, Object>> handleSigningLink(SigningLinkException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case ALREADY_SIGNED -> HttpStatus.CONFLICT;   // 409
            case INVALID        -> HttpStatus.NOT_FOUND;  // 404
            default             -> HttpStatus.GONE;       // 410 — EXPIRED / CANCELLED
        };
        log.warn("Signing link {}: {}", ex.getReason(), ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",    status.value());
        body.put("reason",    ex.getReason().name());
        body.put("message",   ex.getMessage());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }

    /** Resource not found — RuntimeException thrown by services. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex) {
        String msg = ex.getMessage();
        boolean isNotFound = msg != null && msg.toLowerCase().contains("not found");
        HttpStatus status = isNotFound ? HttpStatus.NOT_FOUND : HttpStatus.INTERNAL_SERVER_ERROR;

        if (isNotFound) {
            log.warn("Resource not found: {}", msg);
        } else {
            log.error("Unhandled RuntimeException: {}", msg, ex);
        }

        return ResponseEntity.status(status).body(errorBody(status, msg));
    }

    /** 404 when Spring MVC finds no matching route (needs spring.mvc.throw-exception-if-no-handler-found=true). */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoRoute(NoHandlerFoundException ex) {
        String msg = "No endpoint: " + ex.getHttpMethod() + " " + ex.getRequestURL();
        log.debug("No handler found: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(HttpStatus.NOT_FOUND, msg));
    }

    /** Catch-all for anything else. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
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
