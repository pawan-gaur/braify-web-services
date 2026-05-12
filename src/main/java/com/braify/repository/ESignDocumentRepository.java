package com.braify.repository;

import com.braify.model.ESignDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ESignDocumentRepository extends MongoRepository<ESignDocument, String> {

    List<ESignDocument> findByCreatedByOrderByCreatedAtDesc(String userId);

    List<ESignDocument> findByOrgIdOrderByCreatedAtDesc(String orgId);

    List<ESignDocument> findByCreatedByAndStatusOrderByCreatedAtDesc(
            String userId, ESignDocument.Status status);
}
