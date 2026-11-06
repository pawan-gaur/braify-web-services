package com.braify.feature.webhook.repository;

import com.braify.feature.webhook.model.WebhookEndpoint;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookEndpointRepository extends MongoRepository<WebhookEndpoint, String> {
    List<WebhookEndpoint> findByOrgIdOrderByCreatedAtDesc(String orgId);
    List<WebhookEndpoint> findByOrgIdAndActiveTrue(String orgId);
    Optional<WebhookEndpoint> findByIdAndOrgId(String id, String orgId);
    long countByOrgId(String orgId);
}
