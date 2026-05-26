package com.braify.feature.bulkemail.repository;

import com.braify.feature.bulkemail.model.BulkEmailJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BulkEmailJobRepository extends MongoRepository<BulkEmailJob, String> {

    Page<BulkEmailJob> findByCreatedByOrderByCreatedAtDesc(String createdBy, Pageable pageable);

    Optional<BulkEmailJob> findByIdAndCreatedBy(String id, String createdBy);

    long countByCreatedByAndStatus(String createdBy, BulkEmailJob.JobStatus status);
}
