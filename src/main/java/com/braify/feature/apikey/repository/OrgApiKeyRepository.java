package com.braify.feature.apikey.repository;

import com.braify.feature.apikey.model.OrgApiKey;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrgApiKeyRepository extends MongoRepository<OrgApiKey, String> {

    List<OrgApiKey> findByOrgId(String orgId);

    Optional<OrgApiKey> findByKeyHash(String keyHash);

    List<OrgApiKey> findByOrgIdOrderByCreatedAtDesc(String orgId);

    List<OrgApiKey> findAllByOrderByCreatedAtDesc();
}
