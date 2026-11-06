package com.braify.feature.pdf.repository;

import com.braify.feature.pdf.model.Template;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TemplateRepository extends MongoRepository<Template, String> {

    /** Only active (non-deleted) templates */
    List<Template> findByDeletedFalseOrderByUpdatedAtDesc();

    /** Active templates matching a name substring */
    List<Template> findByDeletedFalseAndNameContainingIgnoreCase(String name);

    /** Active lookup by id (avoids accidentally returning deleted records) */
    Optional<Template> findByIdAndDeletedFalse(String id);

    /**
     * List-page variants: same filter/sort as above but without the heavy editor fields
     * (htmlContent / cssContent / gjsData). Callers that need the content use findById.
     */
    String LIST_FIELDS = "{'htmlContent':0,'cssContent':0,'gjsData':0}";

    @Query(value = "{'deleted': false}", fields = LIST_FIELDS, sort = "{'updatedAt': -1}")
    List<Template> findSummariesByDeletedFalse();

    @Query(value = "{'organizationId': ?0, 'deleted': false}", fields = LIST_FIELDS, sort = "{'updatedAt': -1}")
    List<Template> findSummariesByOrganizationId(String organizationId);

    /** Org-scoped queries */
    List<Template> findByOrganizationIdAndDeletedFalseOrderByUpdatedAtDesc(String organizationId);
    Optional<Template> findByIdAndOrganizationIdAndDeletedFalse(String id, String organizationId);

    /** Per-org code uniqueness (among non-deleted templates). */
    boolean existsByOrganizationIdAndCodeAndDeletedFalse(String organizationId, String code);

    /** Counts */
    long countByDeletedFalse();
    long countByOrganizationIdAndDeletedFalse(String organizationId);

    /** Monthly growth */
    long countByDeletedFalseAndCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
    long countByOrganizationIdAndDeletedFalseAndCreatedAtBetween(String organizationId, java.time.LocalDateTime from, java.time.LocalDateTime to);
}
