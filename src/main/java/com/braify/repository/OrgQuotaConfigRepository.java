package com.braify.repository;

import com.braify.model.OrgQuotaConfig;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrgQuotaConfigRepository extends MongoRepository<OrgQuotaConfig, String> {

    Optional<OrgQuotaConfig> findByOrganizationId(String organizationId);

    boolean existsByOrganizationId(String organizationId);
}
