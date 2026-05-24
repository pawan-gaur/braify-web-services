package com.braify.feature.esign.controller;

import com.braify.feature.esign.dto.BulkBatchResponse;
import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.dto.PageResponse;
import com.braify.feature.esign.service.ESignDocumentService;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "E-Sign — Bulk Batches",
     description = "Endpoints for listing and inspecting bulk-send batches and their constituent documents.")
@RestController
@RequestMapping("/api/esign/batches")
@RequiredArgsConstructor
public class ESignBulkBatchController {

    private final ESignDocumentService documentService;

    /** Inline request body for initBatch. */
    @Data static class InitBatchRequest  { private String label; private int totalRequested; }

    /** Inline request body for finalizeBatch. */
    @Data static class FinalizeBatchRequest { private int totalCreated; private int totalSent; private int totalFailed; }

    @Operation(summary = "Initialise a bulk batch",
               description = "Creates a PROCESSING batch record before the frontend starts its individual-document loop. " +
                             "Returns the batch ID which must be passed as `bulkBatchId` in each subsequent " +
                             "`POST /api/esign/documents` call. Call `PATCH /{batchId}/finalize` when done.")
    @ApiResponse(responseCode = "200", description = "Batch created — includes batch ID")
    @PostMapping("/init")
    public ResponseEntity<BulkBatchResponse> initBatch(
            @RequestBody InitBatchRequest req,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("POST /api/esign/batches/init label='{}' total={} by '{}'",
                req.getLabel(), req.getTotalRequested(), principal.getUsername());
        return ResponseEntity.ok(documentService.initBatch(req.getLabel(), req.getTotalRequested(), principal));
    }

    @Operation(summary = "Finalize a bulk batch",
               description = "Updates the batch with final counters (created / sent / failed) and transitions its status. " +
                             "Call this after the frontend finishes processing all rows.")
    @ApiResponse(responseCode = "200", description = "Batch updated")
    @PatchMapping("/{batchId}/finalize")
    public ResponseEntity<BulkBatchResponse> finalizeBatch(
            @Parameter(description = "Batch ID") @PathVariable String batchId,
            @RequestBody FinalizeBatchRequest req,
            @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("PATCH /api/esign/batches/{}/finalize created={} sent={} failed={} by '{}'",
                batchId, req.getTotalCreated(), req.getTotalSent(), req.getTotalFailed(), principal.getUsername());
        return ResponseEntity.ok(documentService.finalizeBatch(
                batchId, req.getTotalCreated(), req.getTotalSent(), req.getTotalFailed(), principal));
    }

    @Operation(summary = "List my bulk batches (paginated)",
               description = "Returns bulk send batches created by the authenticated user, newest first.")
    @ApiResponse(responseCode = "200", description = "Paginated list of batches")
    @GetMapping
    public ResponseEntity<PageResponse<BulkBatchResponse>> listBatches(
            @Parameter(description = "Zero-based page index (default 0)")  @RequestParam(defaultValue = "0")  int page,
            @Parameter(description = "Page size (default 20, max 100)")    @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.debug("GET /api/esign/batches caller='{}' page={} size={}", principal.getUsername(), page, size);
        return ResponseEntity.ok(documentService.listMyBatches(principal, page, size));
    }

    @Operation(summary = "Get bulk batch detail",
               description = "Returns summary counters for a specific batch. Ownership is enforced.")
    @ApiResponse(responseCode = "200", description = "Batch summary")
    @ApiResponse(responseCode = "404", description = "Batch not found or not owned by caller")
    @GetMapping("/{batchId}")
    public ResponseEntity<BulkBatchResponse> getBatch(
            @Parameter(description = "Batch ID") @PathVariable String batchId,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.debug("GET /api/esign/batches/{} caller='{}'", batchId, principal.getUsername());
        return ResponseEntity.ok(documentService.getBatch(batchId, principal));
    }

    @Operation(summary = "List documents in a batch (paginated)",
               description = "Returns all e-sign documents that belong to the specified batch, newest first. Ownership is enforced.")
    @ApiResponse(responseCode = "200", description = "Paginated list of documents")
    @ApiResponse(responseCode = "404", description = "Batch not found or not owned by caller")
    @GetMapping("/{batchId}/documents")
    public ResponseEntity<PageResponse<DocumentResponse>> listBatchDocuments(
            @Parameter(description = "Batch ID") @PathVariable String batchId,
            @Parameter(description = "Zero-based page index (default 0)")  @RequestParam(defaultValue = "0")  int page,
            @Parameter(description = "Page size (default 20, max 100)")    @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.debug("GET /api/esign/batches/{}/documents caller='{}' page={} size={}",
                batchId, principal.getUsername(), page, size);
        return ResponseEntity.ok(documentService.listBatchDocuments(batchId, principal, page, size));
    }
}
