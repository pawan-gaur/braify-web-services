package com.braify.feature.esign.repository;

import com.braify.feature.esign.model.ESignDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ESignDocumentRepository extends MongoRepository<ESignDocument, String> {

    // ── Existing queries ───────────────────────────────────────────────────────

    List<ESignDocument> findByCreatedByOrderByCreatedAtDesc(String userId);

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

    // ── Completed documents (for avg signing time calculation) ────────────────

    List<ESignDocument> findByOrgIdAndStatus(String orgId, ESignDocument.Status status);

    List<ESignDocument> findByStatus(ESignDocument.Status status);
}
