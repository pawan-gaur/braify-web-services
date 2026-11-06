package com.braify.feature.email.repository;

import com.braify.feature.email.model.EmailTemplateVersion;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailTemplateVersionRepository extends MongoRepository<EmailTemplateVersion, String> {

    /**
     * History list, newest first. Omits htmlContent, cssContent and gjsData (the bulk of each snapshot);
     * restore and GET /versions/{v} load the full document.
     */
    @Query(value = "{'emailTemplateId': ?0}", fields = "{'htmlContent':0,'cssContent':0,'gjsData':0}", sort = "{'version': -1}")
    List<EmailTemplateVersion> findByEmailTemplateIdOrderByVersionDesc(String emailTemplateId);

    Optional<EmailTemplateVersion> findByEmailTemplateIdAndVersion(String emailTemplateId, int version);

    int countByEmailTemplateId(String emailTemplateId);

    void deleteByEmailTemplateId(String emailTemplateId);
}
