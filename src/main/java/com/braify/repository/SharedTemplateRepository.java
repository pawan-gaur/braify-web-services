package com.braify.repository;

import com.braify.model.SharedTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SharedTemplateRepository extends MongoRepository<SharedTemplate, String> {

    List<SharedTemplate> findByTargetOrgIdAndStatusOrderBySharedAtDesc(
            String targetOrgId, SharedTemplate.Status status);

    List<SharedTemplate> findBySourceOrgIdAndStatusOrderBySharedAtDesc(
            String sourceOrgId, SharedTemplate.Status status);

    Optional<SharedTemplate> findByTemplateIdAndTargetOrgIdAndStatus(
            String templateId, String targetOrgId, SharedTemplate.Status status);

    List<SharedTemplate> findByTemplateIdAndStatus(String templateId, SharedTemplate.Status status);

    Optional<SharedTemplate> findByForkedTemplateId(String forkedTemplateId);
}
