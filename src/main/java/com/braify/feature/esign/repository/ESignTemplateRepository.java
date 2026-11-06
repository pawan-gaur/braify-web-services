package com.braify.feature.esign.repository;

import com.braify.feature.esign.model.ESignTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ESignTemplateRepository extends MongoRepository<ESignTemplate, String> {
    List<ESignTemplate> findByOrgIdOrderByUpdatedAtDesc(String orgId);
    Optional<ESignTemplate> findByIdAndOrgId(String id, String orgId);
    long countByOrgId(String orgId);
}
