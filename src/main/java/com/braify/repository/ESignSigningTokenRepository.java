package com.braify.repository;

import com.braify.model.ESignSigningToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ESignSigningTokenRepository extends MongoRepository<ESignSigningToken, String> {

    Optional<ESignSigningToken> findByJti(String jti);

    Optional<ESignSigningToken> findByDocumentIdAndUsedFalseAndRevokedAtIsNull(String documentId);
}
