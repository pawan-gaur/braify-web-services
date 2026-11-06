package com.braify.feature.organization.repository;

import com.braify.feature.organization.model.Organization;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationRepository extends MongoRepository<Organization, String> {

    Optional<Organization> findByCodeAndDeletedFalse(String code);

    List<Organization> findByDeletedFalseOrderByNameAsc();

    /**
     * Fields left out of the platform-admin org list/search: branding (legacy inline logo),
     * billing, and the cloud / email / SMS provider configs (credentials). The detail and
     * config endpoints load the full document.
     */
    String LIST_FIELDS = "{'branding':0,'billing':0,'cloudConfig':0,'emailConfig':0,'smsConfig':0}";

    /** Org list page — same as findByDeletedFalseOrderByNameAsc, without the heavy/secret sub-documents. */
    @Query(value = "{ 'deleted': false }", fields = LIST_FIELDS, sort = "{ 'name': 1 }")
    List<Organization> findSummariesByDeletedFalse();

    boolean existsByCode(String code);

    /** Case-insensitive name/code search (org list page — same projection as findSummariesByDeletedFalse). */
    @Query(value = "{ 'deleted': false, $or: [ { 'name': { $regex: ?0, $options: 'i' } }, { 'code': { $regex: ?0, $options: 'i' } } ] }",
           fields = LIST_FIELDS)
    List<Organization> searchByQuery(String q);
}
