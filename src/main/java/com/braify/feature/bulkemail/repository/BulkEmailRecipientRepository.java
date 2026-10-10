package com.braify.feature.bulkemail.repository;

import com.braify.feature.bulkemail.model.BulkEmailRecipient;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Recipient documents. Paging, counting and bulk writes go through {@code BulkEmailRecipientStore}
 * (MongoTemplate); this interface covers the simple lookups.
 */
@Repository
public interface BulkEmailRecipientRepository extends MongoRepository<BulkEmailRecipient, String> {

    /** Resolve an open/click/unsubscribe hit to its recipient. */
    Optional<BulkEmailRecipient> findFirstByTrackingId(String trackingId);

    long countByJobId(String jobId);

    long deleteByJobId(String jobId);
}
