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

    boolean existsByCode(String code);

    /** Case-insensitive name/code search. */
    @Query("{ 'deleted': false, $or: [ { 'name': { $regex: ?0, $options: 'i' } }, { 'code': { $regex: ?0, $options: 'i' } } ] }")
    List<Organization> searchByQuery(String q);
}
