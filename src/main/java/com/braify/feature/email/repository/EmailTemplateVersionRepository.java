package com.braify.feature.email.repository;

import com.braify.feature.email.model.EmailTemplateVersion;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailTemplateVersionRepository extends MongoRepository<EmailTemplateVersion, String> {

    List<EmailTemplateVersion> findByEmailTemplateIdOrderByVersionDesc(String emailTemplateId);

    Optional<EmailTemplateVersion> findByEmailTemplateIdAndVersion(String emailTemplateId, int version);

    int countByEmailTemplateId(String emailTemplateId);

    void deleteByEmailTemplateId(String emailTemplateId);
}
