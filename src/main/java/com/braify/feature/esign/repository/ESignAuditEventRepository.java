package com.braify.feature.esign.repository;

import com.braify.feature.esign.model.ESignAuditEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ESignAuditEventRepository extends MongoRepository<ESignAuditEvent, String> {

    /** Returns the complete audit trail for a document, oldest first. */
    List<ESignAuditEvent> findByDocumentIdOrderByTimestampAsc(String documentId);
}
