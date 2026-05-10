package com.braify.repository;

import com.braify.model.TemplateVersion;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TemplateVersionRepository extends MongoRepository<TemplateVersion, String> {

    /** All versions for a template, newest first */
    List<TemplateVersion> findByTemplateIdOrderByVersionDesc(String templateId);

    /** Specific version for a template */
    Optional<TemplateVersion> findByTemplateIdAndVersion(String templateId, int version);

    /** Count of saved versions — used to compute the next version number */
    int countByTemplateId(String templateId);

    /** Useful for cleaning up orphaned versions after a hard delete */
    void deleteByTemplateId(String templateId);
}
