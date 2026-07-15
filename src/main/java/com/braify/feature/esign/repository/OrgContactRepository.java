package com.braify.feature.esign.repository;

import com.braify.feature.esign.model.OrgContact;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrgContactRepository extends MongoRepository<OrgContact, String> {

    /** Contacts for an org, ordered by the caller-supplied {@link Pageable} sort. */
    List<OrgContact> findByOrgId(String orgId, Pageable pageable);
}
