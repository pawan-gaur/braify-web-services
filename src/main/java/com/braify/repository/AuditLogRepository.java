package com.braify.repository;

import com.braify.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface AuditLogRepository extends MongoRepository<AuditLog, String> {

    // ── Single resource ────────────────────────────────────────────────────────

    /** All logs for a specific resource id (templateId or userId), newest first */
    List<AuditLog> findByTemplateIdOrderByTimestampDesc(String templateId);

    // ── PLATFORM_ADMIN: paginated global log ───────────────────────────────────

    Page<AuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    Page<AuditLog> findByResourceTypeOrderByTimestampDesc(
            AuditLog.ResourceType resourceType, Pageable pageable);

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

    List<AuditLog> findTop10ByOrderByTimestampDesc();

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
