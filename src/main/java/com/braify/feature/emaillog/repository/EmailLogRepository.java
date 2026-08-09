package com.braify.feature.emaillog.repository;

import com.braify.feature.emaillog.model.EmailLog;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for {@link EmailLog}. Filtered/paged queries for the viewer are built dynamically
 * via {@code MongoTemplate} in {@code EmailLogService} (role scope + optional category/status/date/search),
 * so this interface only needs the base CRUD.
 */
public interface EmailLogRepository extends MongoRepository<EmailLog, String> {
}
