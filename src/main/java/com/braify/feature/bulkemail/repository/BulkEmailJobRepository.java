package com.braify.feature.bulkemail.repository;

import com.braify.feature.bulkemail.model.BulkEmailJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BulkEmailJobRepository extends MongoRepository<BulkEmailJob, String> {

    // ── User-scoped (own jobs) ────────────────────────────────────────────────
    Page<BulkEmailJob> findByCreatedByOrderByCreatedAtDesc(String createdBy, Pageable pageable);
    Optional<BulkEmailJob> findByIdAndCreatedBy(String id, String createdBy);
    long countByCreatedByAndStatus(String createdBy, BulkEmailJob.JobStatus status);

    // ── Org-scoped (ORG_ADMIN sees all jobs in their org) ─────────────────────
    Page<BulkEmailJob> findByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);
    Optional<BulkEmailJob> findByIdAndOrgId(String id, String orgId);

    // ── Cross-org (PLATFORM_ADMIN sees all jobs) ──────────────────────────────
    Page<BulkEmailJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
