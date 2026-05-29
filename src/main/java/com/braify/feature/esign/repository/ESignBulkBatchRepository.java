package com.braify.feature.esign.repository;

import com.braify.feature.esign.model.ESignBulkBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ESignBulkBatchRepository extends MongoRepository<ESignBulkBatch, String> {

    // ── User-scoped ───────────────────────────────────────────────────────────
    Page<ESignBulkBatch> findByCreatedByOrderByCreatedAtDesc(String userId, Pageable pageable);

    Page<ESignBulkBatch> findByCreatedByAndStatusOrderByCreatedAtDesc(
            String userId, ESignBulkBatch.Status status, Pageable pageable);

    // ── Org-scoped (ORG_ADMIN sees all batches in their org) ──────────────────
    Page<ESignBulkBatch> findByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);

    // ── Cross-org (PLATFORM_ADMIN sees all batches) ───────────────────────────
    Page<ESignBulkBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
