package com.braify.feature.platform.repository;

import com.braify.feature.platform.model.PlatformProviderDefaults;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlatformProviderDefaultsRepository
        extends MongoRepository<PlatformProviderDefaults, String> {
}
