package com.braify.feature.esign.repository;

import com.braify.feature.esign.model.ESignAuditEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ESignAuditEventRepository extends MongoRepository<ESignAuditEvent, String> {

    /** Returns the complete audit trail for a document, oldest first. */
    List<ESignAuditEvent> findByDocumentIdOrderByTimestampAsc(String documentId);

    /** Counts all events of a given type across all documents (PLATFORM_ADMIN). */
    long countByEvent(ESignAuditEvent.EventType event);

    /** Counts events of a given type scoped to a set of document IDs (org-scoped). */
    long countByEventAndDocumentIdIn(ESignAuditEvent.EventType event, List<String> documentIds);
}
