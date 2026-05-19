package com.braify.feature.pdf.repository;

import com.braify.feature.pdf.model.Template;
import org.springframework.data.mongodb.repository.MongoRepository;
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

    /** Org-scoped queries */
    List<Template> findByOrganizationIdAndDeletedFalseOrderByUpdatedAtDesc(String organizationId);
    Optional<Template> findByIdAndOrganizationIdAndDeletedFalse(String id, String organizationId);

    /** Counts */
    long countByDeletedFalse();
    long countByOrganizationIdAndDeletedFalse(String organizationId);

    /** Monthly growth */
    long countByDeletedFalseAndCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
    long countByOrganizationIdAndDeletedFalseAndCreatedAtBetween(String organizationId, java.time.LocalDateTime from, java.time.LocalDateTime to);
}
