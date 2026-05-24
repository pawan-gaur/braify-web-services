package com.braify.feature.esign.repository;

import com.braify.feature.esign.model.ESignBulkBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ESignBulkBatchRepository extends MongoRepository<ESignBulkBatch, String> {

    Page<ESignBulkBatch> findByCreatedByOrderByCreatedAtDesc(String userId, Pageable pageable);

    Page<ESignBulkBatch> findByCreatedByAndStatusOrderByCreatedAtDesc(
            String userId, ESignBulkBatch.Status status, Pageable pageable);
}
