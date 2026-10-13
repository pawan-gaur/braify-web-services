package com.braify.feature.signer.repository;

import com.braify.feature.signer.model.SignerAccount;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SignerAccountRepository extends MongoRepository<SignerAccount, String> {

    /** {@code email} must already be normalized (trimmed, lower-case). */
    Optional<SignerAccount> findByEmail(String email);
}
