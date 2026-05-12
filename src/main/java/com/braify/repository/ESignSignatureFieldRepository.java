package com.braify.repository;

import com.braify.model.ESignSignatureField;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ESignSignatureFieldRepository extends MongoRepository<ESignSignatureField, String> {

    List<ESignSignatureField> findByDocumentIdOrderByPageAscYAsc(String documentId);

    void deleteByDocumentId(String documentId);
}
