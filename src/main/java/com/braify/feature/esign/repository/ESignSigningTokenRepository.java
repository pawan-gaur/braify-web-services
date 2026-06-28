package com.braify.feature.esign.repository;

import com.braify.feature.esign.model.ESignSigningToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ESignSigningTokenRepository extends MongoRepository<ESignSigningToken, String> {

    Optional<ESignSigningToken> findByJti(String jti);

    Optional<ESignSigningToken> findByDocumentIdAndUsedFalseAndRevokedAtIsNull(String documentId);

    /** Active (unused, unrevoked) token for a specific signatory of a document. */
    Optional<ESignSigningToken> findByDocumentIdAndSignatoryIdAndUsedFalseAndRevokedAtIsNull(
            String documentId, String signatoryId);

    /**
     * Targeted query used by {@link com.braify.feature.esign.service.ESignTokenService#expireStaleTokens()}
     * to fetch only the expired tokens — avoids loading all tokens including used/revoked ones.
     */
    List<ESignSigningToken> findByUsedFalseAndRevokedAtIsNullAndExpiresAtBefore(LocalDateTime cutoff);
}
