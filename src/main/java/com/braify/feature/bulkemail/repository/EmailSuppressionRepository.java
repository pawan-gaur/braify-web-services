package com.braify.feature.bulkemail.repository;

import com.braify.feature.bulkemail.model.EmailSuppression;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface EmailSuppressionRepository extends MongoRepository<EmailSuppression, String> {

    List<EmailSuppression> findByOrgId(String orgId);

    boolean existsByOrgIdAndEmail(String orgId, String email);

    Optional<EmailSuppression> findByOrgIdAndEmail(String orgId, String email);
}
