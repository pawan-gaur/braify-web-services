package com.braify.feature.audit.repository;

import com.braify.feature.audit.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditLogRepository extends MongoRepository<AuditLog, String> {

    // ── Integrity chain ─────────────────────────────────────────────────────────

    /** Current head of the hash chain (most-recent v2-hashed entry). */
    Optional<AuditLog> findTopByHashVersionOrderByTimestampDescIdDesc(int hashVersion);

    /** v2-hashed entries in chain (insertion) order — for integrity verification. */
    List<AuditLog> findByHashVersionGreaterThanEqualOrderByTimestampAscIdAsc(int hashVersion, Pageable pageable);

    /** Count of legacy (pre-v2) entries that can't be chain-verified. */
    long countByHashVersionLessThan(int hashVersion);

    // ── Single resource ────────────────────────────────────────────────────────

    /** All logs for a specific resource id (templateId or userId), newest first */
    List<AuditLog> findByTemplateIdOrderByTimestampDesc(String templateId);

    // ── PLATFORM_ADMIN: paginated global log ───────────────────────────────────

    Page<AuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    Page<AuditLog> findByResourceTypeOrderByTimestampDesc(
            AuditLog.ResourceType resourceType, Pageable pageable);

    // ── PLATFORM_ADMIN: org-scoped log (filter by organizationId) ─────────────

    Page<AuditLog> findByOrganizationIdOrderByTimestampDesc(
            String organizationId, Pageable pageable);

    Page<AuditLog> findByOrganizationIdAndResourceTypeOrderByTimestampDesc(
            String organizationId, AuditLog.ResourceType resourceType, Pageable pageable);

    // ── ORG_ADMIN / ADMIN: scoped to a set of performer emails ────────────────

    /** Paginated log filtered by a set of performer emails */
    Page<AuditLog> findByPerformedByInOrderByTimestampDesc(
            Collection<String> performerEmails, Pageable pageable);

    /** Same but also filtered by resource type */
    Page<AuditLog> findByPerformedByInAndResourceTypeOrderByTimestampDesc(
            Collection<String> performerEmails, AuditLog.ResourceType resourceType, Pageable pageable);

    // ── USER: own activity only ────────────────────────────────────────────────

    Page<AuditLog> findByPerformedByOrderByTimestampDesc(
            String performedBy, Pageable pageable);

    Page<AuditLog> findByPerformedByAndResourceTypeOrderByTimestampDesc(
            String performedBy, AuditLog.ResourceType resourceType, Pageable pageable);

    // ── Dashboard ──────────────────────────────────────────────────────────────

    /** Global top-10 — PLATFORM_ADMIN only. */
    List<AuditLog> findTop10ByOrderByTimestampDesc();

    /** Org-scoped top-10 — used for non-PLATFORM_ADMIN dashboard activity feed. */
    List<AuditLog> findTop10ByOrganizationIdOrderByTimestampDesc(String organizationId);

    /**
     * Top-10 most-recent logs where the performer is in the given email set.
     * Used for role-scoped recent-activity on the dashboard (ORG_ADMIN / ADMIN / USER).
     */
    List<AuditLog> findTop10ByPerformedByInOrderByTimestampDesc(Collection<String> performerEmails);

    /** All logs for an org since a given timestamp — used for top-user ranking. */
    List<AuditLog> findByOrganizationIdAndTimestampAfter(String organizationId, LocalDateTime after);

    /** Global logs since a given timestamp — used for top-user ranking (PLATFORM_ADMIN). */
    List<AuditLog> findByTimestampAfter(LocalDateTime after);

    /**
     * Logs since a given timestamp where the performer is in the given email set.
     * Used for role-scoped top-user ranking (ORG_ADMIN / ADMIN / USER).
     */
    List<AuditLog> findByPerformedByInAndTimestampAfter(Collection<String> performerEmails, LocalDateTime after);

    @Query("{ 'performedBy': { $in: ?0 }, 'resourceType': { $in: ?1 } }")
    List<AuditLog> findRecentByPerformersAndTypes(
            List<String> performerEmails,
            List<String> resourceTypes,
            Pageable pageable);

    long countByActionAndResourceTypeAndTimestampBetween(
            AuditLog.Action action,
            AuditLog.ResourceType resourceType,
            LocalDateTime from,
            LocalDateTime to);

    @Query("{ 'action': ?0, 'resourceType': ?1, 'performedBy': { $in: ?2 }, 'timestamp': { $gte: ?3, $lte: ?4 } }")
    long countByActionAndResourceTypeAndPerformersAndTimestampBetween(
            String action, String resourceType,
            List<String> performers,
            LocalDateTime from, LocalDateTime to);
}
