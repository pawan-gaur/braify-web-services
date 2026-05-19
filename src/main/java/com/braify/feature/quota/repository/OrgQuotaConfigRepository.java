package com.braify.feature.quota.repository;

import com.braify.feature.quota.model.OrgQuotaConfig;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrgQuotaConfigRepository extends MongoRepository<OrgQuotaConfig, String> {

    Optional<OrgQuotaConfig> findByOrganizationId(String organizationId);

    boolean existsByOrganizationId(String organizationId);
}
