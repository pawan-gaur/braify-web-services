package com.braify.feature.auth.repository;

import com.braify.feature.auth.model.InvitationToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InvitationTokenRepository extends MongoRepository<InvitationToken, String> {

    Optional<InvitationToken> findByTokenAndUsedFalse(String token);

    /** Useful to invalidate all pending tokens for a user before issuing a new one. */
    java.util.List<InvitationToken> findByUserIdAndTypeAndUsedFalse(
            String userId, InvitationToken.TokenType type);
}
