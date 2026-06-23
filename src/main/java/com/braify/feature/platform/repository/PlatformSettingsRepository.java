package com.braify.feature.platform.repository;

import com.braify.feature.platform.model.PlatformSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlatformSettingsRepository extends MongoRepository<PlatformSettings, String> {
}
