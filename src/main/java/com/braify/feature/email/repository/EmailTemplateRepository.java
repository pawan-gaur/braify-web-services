package com.braify.feature.email.repository;

import com.braify.feature.email.model.EmailTemplate;
import com.braify.shared.TemplateType;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailTemplateRepository extends MongoRepository<EmailTemplate, String> {

    List<EmailTemplate> findByDeletedFalseOrderByUpdatedAtDesc();

    List<EmailTemplate> findByDeletedFalseAndNameContainingIgnoreCase(String name);

    Optional<EmailTemplate> findByIdAndDeletedFalse(String id);

    /** INTERNAL system templates are resolved by their stable code. */
    Optional<EmailTemplate> findByCodeAndDeletedFalse(String code);

    /** Type-scoped listing — the user-facing template UI shows EXTERNAL only. */
    List<EmailTemplate> findByTypeAndDeletedFalseOrderByUpdatedAtDesc(TemplateType type);
    List<EmailTemplate> findByOrganizationIdAndTypeAndDeletedFalseOrderByUpdatedAtDesc(String organizationId, TemplateType type);

    /**
     * List-page variants of the type-scoped queries above, without the heavy editor fields
     * (htmlContent / cssContent / gjsData). Callers that need the content use findById.
     */
    String LIST_FIELDS = "{'htmlContent':0,'cssContent':0,'gjsData':0}";

    @Query(value = "{'type': ?0, 'deleted': false}", fields = LIST_FIELDS, sort = "{'updatedAt': -1}")
    List<EmailTemplate> findSummariesByType(TemplateType type);

    @Query(value = "{'organizationId': ?0, 'type': ?1, 'deleted': false}", fields = LIST_FIELDS, sort = "{'updatedAt': -1}")
    List<EmailTemplate> findSummariesByOrganizationIdAndType(String organizationId, TemplateType type);

    /** Org-scoped queries */
    List<EmailTemplate> findByOrganizationIdAndDeletedFalseOrderByUpdatedAtDesc(String organizationId);
    Optional<EmailTemplate> findByIdAndOrganizationIdAndDeletedFalse(String id, String organizationId);

    /** Per-org code uniqueness (among non-deleted templates). */
    boolean existsByOrganizationIdAndCodeAndDeletedFalse(String organizationId, String code);

    /** True if a code is reserved by an INTERNAL system template (must not be shadowed by a user code). */
    boolean existsByCodeAndTypeAndDeletedFalse(String code, TemplateType type);

    /** Counts */
    long countByDeletedFalse();
    long countByOrganizationIdAndDeletedFalse(String organizationId);

    /** Monthly growth */
    long countByDeletedFalseAndCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
    long countByOrganizationIdAndDeletedFalseAndCreatedAtBetween(String organizationId, java.time.LocalDateTime from, java.time.LocalDateTime to);
}
