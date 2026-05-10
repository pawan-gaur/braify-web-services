package com.braify.repository;

import com.braify.model.EmailTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailTemplateRepository extends MongoRepository<EmailTemplate, String> {

    List<EmailTemplate> findByDeletedFalseOrderByUpdatedAtDesc();

    List<EmailTemplate> findByDeletedFalseAndNameContainingIgnoreCase(String name);

    Optional<EmailTemplate> findByIdAndDeletedFalse(String id);

    /** Org-scoped queries */
    List<EmailTemplate> findByOrganizationIdAndDeletedFalseOrderByUpdatedAtDesc(String organizationId);
    Optional<EmailTemplate> findByIdAndOrganizationIdAndDeletedFalse(String id, String organizationId);

    /** Counts */
    long countByDeletedFalse();
    long countByOrganizationIdAndDeletedFalse(String organizationId);

    /** Monthly growth */
    long countByDeletedFalseAndCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
    long countByOrganizationIdAndDeletedFalseAndCreatedAtBetween(String organizationId, java.time.LocalDateTime from, java.time.LocalDateTime to);
}
