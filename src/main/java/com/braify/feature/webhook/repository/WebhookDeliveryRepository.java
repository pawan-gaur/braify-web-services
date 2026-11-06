package com.braify.feature.webhook.repository;

import com.braify.feature.webhook.model.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WebhookDeliveryRepository extends MongoRepository<WebhookDelivery, String> {
    Page<WebhookDelivery> findByEndpointIdOrderByCreatedAtDesc(String endpointId, Pageable pageable);
}
