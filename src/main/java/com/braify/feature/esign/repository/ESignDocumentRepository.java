package com.braify.feature.esign.repository;

import com.braify.feature.esign.model.ESignDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ESignDocumentRepository extends MongoRepository<ESignDocument, String> {

    // ── Existing queries ───────────────────────────────────────────────────────

    List<ESignDocument> findByCreatedByOrderByCreatedAtDesc(String userId);

    // ── Paginated single-document queries (bulkBatchId IS NULL) ───────────────

    /** User-scoped: own documents (ADMIN / USER). */
    Page<ESignDocument> findByCreatedByAndBulkBatchIdIsNullOrderByCreatedAtDesc(
            String userId, Pageable pageable);

    Page<ESignDocument> findByCreatedByAndBulkBatchIdIsNullAndStatusOrderByCreatedAtDesc(
            String userId, ESignDocument.Status status, Pageable pageable);

    /** Org-scoped: all documents in an org (ORG_ADMIN). */
    Page<ESignDocument> findByOrgIdAndBulkBatchIdIsNullOrderByCreatedAtDesc(
            String orgId, Pageable pageable);

    Page<ESignDocument> findByOrgIdAndBulkBatchIdIsNullAndStatusOrderByCreatedAtDesc(
            String orgId, ESignDocument.Status status, Pageable pageable);

    /** Cross-org: all documents (PLATFORM_ADMIN). */
    Page<ESignDocument> findByBulkBatchIdIsNullOrderByCreatedAtDesc(Pageable pageable);

    Page<ESignDocument> findByBulkBatchIdIsNullAndStatusOrderByCreatedAtDesc(
            ESignDocument.Status status, Pageable pageable);

    // ── Paginated queries for documents belonging to a batch ──────────────────

    Page<ESignDocument> findByBulkBatchIdOrderByCreatedAtDesc(
            String bulkBatchId, Pageable pageable);

    List<ESignDocument> findByOrgIdOrderByCreatedAtDesc(String orgId);

    List<ESignDocument> findByCreatedByAndStatusOrderByCreatedAtDesc(
            String userId, ESignDocument.Status status);

    // ── Count by single status ─────────────────────────────────────────────────

    long countByOrgIdAndStatus(String orgId, ESignDocument.Status status);

    long countByStatus(ESignDocument.Status status);

    // ── Count by multiple statuses ─────────────────────────────────────────────

    long countByOrgIdAndStatusIn(String orgId, List<ESignDocument.Status> statuses);

    long countByStatusIn(List<ESignDocument.Status> statuses);

    // ── Total by org ───────────────────────────────────────────────────────────

    long countByOrgId(String orgId);

    // ── Sent (sentAt not null) ─────────────────────────────────────────────────

    long countByOrgIdAndSentAtIsNotNull(String orgId);

    long countBySentAtIsNotNull();

    // ── Monthly sent trend ─────────────────────────────────────────────────────

    long countByOrgIdAndSentAtBetween(String orgId, LocalDateTime from, LocalDateTime to);

    long countBySentAtBetween(LocalDateTime from, LocalDateTime to);

    // ── Pending/In-Review docs (for overdue calculation in service layer) ────────

    List<ESignDocument> findByOrgIdAndStatusIn(String orgId, List<ESignDocument.Status> statuses);

    List<ESignDocument> findByStatusIn(List<ESignDocument.Status> statuses);

    /**
     * Used by {@link com.braify.feature.esign.service.ESignExpiryScheduler} to find
     * documents that need to be expired without loading the entire collection.
     *
     * <p>Previously the scheduler called {@code findAll()} which loaded every document
     * including embedded PDF byte arrays ({@code sourcePdfData}, {@code signedPdfData})
     * into JVM heap on every hourly tick. This targeted query fetches only the matching
     * subset.
     */
    List<ESignDocument> findByStatusInAndTokenExpiresAtBefore(
            List<ESignDocument.Status> statuses,
            java.time.LocalDateTime cutoff);

    /**
     * Candidates for the reminder scheduler: still-open documents whose signing window has
     * NOT yet closed (token expires in the future). The scheduler further filters these by
     * per-document {@code remindersEnabled} and per-signatory timing/cap in memory.
     */
    List<ESignDocument> findByStatusInAndTokenExpiresAtAfter(
            List<ESignDocument.Status> statuses,
            java.time.LocalDateTime cutoff);

    // ── Completed documents (for avg signing time calculation) ────────────────

    List<ESignDocument> findByOrgIdAndStatus(String orgId, ESignDocument.Status status);

    List<ESignDocument> findByStatus(ESignDocument.Status status);

    // ── Overdue counts — count expired pending docs WITHOUT loading them ───────
    // (the dashboard previously loaded every pending doc, incl. embedded PDF bytes,
    //  just to filter+count by tokenExpiresAt < now)

    long countByStatusInAndTokenExpiresAtBefore(
            List<ESignDocument.Status> statuses, LocalDateTime cutoff);

    long countByOrgIdAndStatusInAndTokenExpiresAtBefore(
            String orgId, List<ESignDocument.Status> statuses, LocalDateTime cutoff);

    // ── ID-only projection — for the viewed-count, avoids loading PDF byte[]s ──
    @Query(value = "{ 'orgId': ?0 }", fields = "{ '_id': 1 }")
    List<ESignDocument> findIdsByOrgId(String orgId);
}
