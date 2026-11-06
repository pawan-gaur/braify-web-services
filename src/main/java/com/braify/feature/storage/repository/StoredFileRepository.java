package com.braify.feature.storage.repository;

import com.braify.feature.storage.model.StoredFile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface StoredFileRepository extends MongoRepository<StoredFile, String> {

    Optional<StoredFile> findByIdAndDeletedAtIsNull(String id);
}
