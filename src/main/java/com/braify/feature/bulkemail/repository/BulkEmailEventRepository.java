package com.braify.feature.bulkemail.repository;

import com.braify.feature.bulkemail.model.BulkEmailEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BulkEmailEventRepository extends MongoRepository<BulkEmailEvent, String> {

    long countByJobIdAndType(String jobId, BulkEmailEvent.Type type);
}
