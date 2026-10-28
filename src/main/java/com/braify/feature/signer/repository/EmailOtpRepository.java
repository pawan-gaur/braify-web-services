package com.braify.feature.signer.repository;

import com.braify.feature.signer.model.EmailOtp;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailOtpRepository extends MongoRepository<EmailOtp, String> {

    /** All unconsumed codes — invalidated when a new code is issued. */
    List<EmailOtp> findByEmailAndConsumedFalse(String email);

    /** Codes issued since {@code since} — used for the per-hour send cap. */
    long countByEmailAndCreatedAtAfter(String email, LocalDateTime since);

    Optional<EmailOtp> findFirstByEmailOrderByCreatedAtDesc(String email);
}
