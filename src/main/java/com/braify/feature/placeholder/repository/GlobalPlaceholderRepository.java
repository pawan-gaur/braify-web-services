package com.braify.feature.placeholder.repository;

import com.braify.feature.placeholder.model.GlobalPlaceholder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface GlobalPlaceholderRepository extends MongoRepository<GlobalPlaceholder, String> {

    List<GlobalPlaceholder> findByOrganizationIdOrderByKeyAsc(String organizationId);

    Optional<GlobalPlaceholder> findByIdAndOrganizationId(String id, String organizationId);

    boolean existsByOrganizationIdAndKey(String organizationId, String key);
}
