package com.braify.feature.emaillog.controller;

import com.braify.feature.emaillog.dto.EmailLogResponse;
import com.braify.feature.emaillog.model.EmailLog;
import com.braify.feature.emaillog.service.EmailLogService;
import com.braify.feature.esign.dto.PageResponse;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Email Activity viewer. Role-scoped in {@link EmailLogService#list}:
 * PLATFORM_ADMIN sees all orgs (optionally filtered to one via {@code orgId}), ORG_ADMIN sees only
 * their own org's emails. Regular users are denied.
 */
@Slf4j
@RestController
@RequestMapping("/api/email-logs")
@RequiredArgsConstructor
@Tag(name = "Email Activity", description = "Audit log of every outbound email")
public class EmailLogController {

    private final EmailLogService emailLogService;

    @Operation(summary = "List email activity",
               description = "Paginated, role-scoped list of sent emails with optional category/status/date/search filters.")
    @ApiResponse(responseCode = "200", description = "Page of email-log rows")
    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<PageResponse<EmailLogResponse>> list(
            @Parameter(description = "Filter to one organization (PLATFORM_ADMIN only; ignored for ORG_ADMIN)")
                @RequestParam(required = false) String orgId,
            @Parameter(description = "Filter by category (optional)") @RequestParam(required = false) String category,
            @Parameter(description = "Filter by status SENT|FAILED (optional)") @RequestParam(required = false) String status,
            @Parameter(description = "Recipient type: ALL (default), TO (primary only), or CC (CC recipients only)")
                @RequestParam(required = false) String recipientType,
            @Parameter(description = "Free-text search on recipient/subject/sender (optional)") @RequestParam(required = false) String search,
            @Parameter(description = "Sent on/after this instant or yyyy-MM-dd (optional)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Sent on/before this instant or yyyy-MM-dd (optional)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Zero-based page index (default 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (default 20, max 100)") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        EmailLog.Category categoryEnum = parseEnum(EmailLog.Category.class, category);
        EmailLog.Status   statusEnum   = parseEnum(EmailLog.Status.class, status);
        Boolean ccFilter = parseRecipientType(recipientType);

        log.debug("GET /api/email-logs caller='{}' org={} category={} status={} recipientType={} search='{}' page={} size={}",
                principal.getUsername(), orgId, category, status, recipientType, search, page, size);

        return ResponseEntity.ok(emailLogService.list(
                principal, orgId, categoryEnum, statusEnum, ccFilter, search,
                parseFrom(dateFrom), parseTo(dateTo), page, size));
    }

    /** Maps recipientType → the cc flag filter: CC → true, TO → false, ALL/blank/unknown → null (no filter). */
    private Boolean parseRecipientType(String v) {
        if (v == null || v.isBlank()) return null;
        return switch (v.trim().toUpperCase()) {
            case "CC" -> Boolean.TRUE;
            case "TO" -> Boolean.FALSE;
            default   -> null;
        };
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String v) {
        if (v == null || v.isBlank()) return null;
        try { return Enum.valueOf(type, v.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }  // unknown → no filter
    }

    /** Lower bound as UTC LocalDateTime; accepts an ISO instant or plain yyyy-MM-dd (start of day). */
    private LocalDateTime parseFrom(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        try { return LocalDateTime.ofInstant(Instant.parse(s), ZoneOffset.UTC); } catch (Exception ignore) { }
        try { return LocalDate.parse(s).atStartOfDay(); } catch (Exception e) { return null; }
    }

    /** Upper bound as UTC LocalDateTime; a plain yyyy-MM-dd becomes the end of that day. */
    private LocalDateTime parseTo(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        try { return LocalDateTime.ofInstant(Instant.parse(s), ZoneOffset.UTC); } catch (Exception ignore) { }
        try { return LocalDate.parse(s).atTime(23, 59, 59); } catch (Exception e) { return null; }
    }
}
