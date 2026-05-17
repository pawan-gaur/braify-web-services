package com.braify.repository;

import com.braify.model.ApiKeyUsageLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ApiKeyUsageLogRepository extends MongoRepository<ApiKeyUsageLog, String> {

    List<ApiKeyUsageLog> findByOrgIdAndCalledAtAfterOrderByCalledAtDesc(String orgId, LocalDateTime after);

    List<ApiKeyUsageLog> findByApiKeyIdAndCalledAtAfterOrderByCalledAtDesc(String apiKeyId, LocalDateTime after);

    long countByApiKeyIdAndCalledAtAfter(String apiKeyId, LocalDateTime after);
}
