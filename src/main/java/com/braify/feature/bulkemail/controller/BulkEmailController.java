package com.braify.feature.bulkemail.controller;

import com.braify.feature.bulkemail.dto.BulkEmailJobRequest;
import com.braify.feature.bulkemail.dto.BulkEmailJobResponse;
import com.braify.feature.bulkemail.service.BulkEmailService;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.braify.feature.bulkemail.dto.BulkEmailJobResponse.AuditEventResponse;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "Bulk Email", description = "Create and monitor bulk email send jobs. Each job processes rows from an uploaded XLSX file, sends emails via the selected template, and optionally attaches a PDF. All routes require a valid user JWT.")
@RestController
@RequestMapping("/api/bulk-email/jobs")
@RequiredArgsConstructor
public class BulkEmailController {

    private final BulkEmailService bulkEmailService;

    @Operation(summary = "Create and start bulk email job",
               description = "Parses the rows array, creates a job record, and starts async email dispatch immediately. " +
                             "Rows with blank/missing email values are silently skipped. " +
                             "Returns the created job (without row details) with status PENDING — " +
                             "poll GET /{id} to track progress.")
    @ApiResponse(responseCode = "200", description = "Job created and processing started")
    @ApiResponse(responseCode = "400", description = "Validation error (missing template, no valid rows, etc.)")
    @PostMapping
    public ResponseEntity<BulkEmailJobResponse> createJob(
            @Valid @RequestBody BulkEmailJobRequest req,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("POST /api/bulk-email/jobs rows={} template='{}' attachment={} by '{}'",
                req.getRows() != null ? req.getRows().size() : 0,
                req.getEmailTemplateId(), req.getAttachmentType(), principal.getUsername());
        BulkEmailJobResponse job = bulkEmailService.createJob(req, principal);
        log.info("BulkEmailJob '{}' started", job.getId());
        return ResponseEntity.ok(job);
    }

    @Operation(summary = "List bulk email jobs (paginated)",
               description = "Returns jobs created by the authenticated user, newest first. " +
                             "Row details are excluded from the list — fetch a single job for row-level status.")
    @ApiResponse(responseCode = "200", description = "Paginated job list")
    @GetMapping
    public ResponseEntity<Page<BulkEmailJobResponse>> listJobs(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0")  int page,
            @Parameter(description = "Page size (max 50)")    @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        return ResponseEntity.ok(bulkEmailService.listJobs(principal, page, size));
    }

    @Operation(summary = "Get job details",
               description = "Returns full job including per-row status, errors, and Resend message IDs. " +
                             "Poll this endpoint while status is PROCESSING to get live progress.")
    @ApiResponse(responseCode = "200", description = "Job details with row results")
    @ApiResponse(responseCode = "404", description = "Job not found or not owned by caller")
    @GetMapping("/{id}")
    public ResponseEntity<BulkEmailJobResponse> getJob(
            @Parameter(description = "Job ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        return ResponseEntity.ok(bulkEmailService.getJob(id, principal));
    }

    @Operation(summary = "Resend failed rows",
               description = "Creates a new job using the same configuration but only the rows that previously failed. " +
                             "The original job is left unchanged. Returns the new resend job.")
    @ApiResponse(responseCode = "200", description = "Resend job created")
    @ApiResponse(responseCode = "400", description = "No failed rows to resend")
    @PostMapping("/{id}/resend")
    public ResponseEntity<BulkEmailJobResponse> resendFailed(
            @Parameter(description = "Original job ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("POST /api/bulk-email/jobs/{}/resend by '{}'", id, principal.getUsername());
        BulkEmailJobResponse job = bulkEmailService.resendFailed(id, principal);
        log.info("Resend job '{}' created from '{}'", job.getId(), id);
        return ResponseEntity.ok(job);
    }

    @Operation(summary = "Cancel a running job",
               description = "Requests cancellation of a PENDING or PROCESSING job. " +
                             "Emails already dispatched are NOT recalled — cancellation prevents future rows from sending.")
    @ApiResponse(responseCode = "200", description = "Job cancelled")
    @ApiResponse(responseCode = "400", description = "Job already in terminal state")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<BulkEmailJobResponse> cancelJob(
            @Parameter(description = "Job ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("POST /api/bulk-email/jobs/{}/cancel by '{}'", id, principal.getUsername());
        return ResponseEntity.ok(bulkEmailService.cancelJob(id, principal));
    }

    @Operation(summary = "Get audit trail for a job",
               description = "Returns the chronological list of audit events for the given job.")
    @ApiResponse(responseCode = "200", description = "Audit events")
    @ApiResponse(responseCode = "404", description = "Job not found or not owned by caller")
    @GetMapping("/{id}/audit")
    public ResponseEntity<List<AuditEventResponse>> getAudit(
            @Parameter(description = "Job ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        BulkEmailJobResponse job = bulkEmailService.getJob(id, principal);
        List<AuditEventResponse> events = job.getAuditEvents() != null ? job.getAuditEvents() : List.of();
        return ResponseEntity.ok(events);
    }

    @Operation(summary = "Get job summary counters",
               description = "Lightweight endpoint — returns only the status and counters (no row list). " +
                             "Suitable for high-frequency polling from the UI progress bar.")
    @ApiResponse(responseCode = "200", description = "Job counters")
    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @Parameter(description = "Job ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        BulkEmailJobResponse job = bulkEmailService.getJob(id, principal);
        return ResponseEntity.ok(Map.of(
                "id",           job.getId(),
                "status",       job.getStatus(),
                "totalCount",   job.getTotalCount(),
                "sentCount",    job.getSentCount(),
                "failedCount",  job.getFailedCount(),
                "pendingCount", job.getPendingCount()
        ));
    }
}
