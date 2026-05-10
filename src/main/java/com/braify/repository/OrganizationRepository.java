package com.braify.repository;

import com.braify.model.Organization;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationRepository extends MongoRepository<Organization, String> {

    Optional<Organization> findBySlugAndDeletedFalse(String slug);

    List<Organization> findByDeletedFalseOrderByNameAsc();

    boolean existsBySlug(String slug);

    /** Case-insensitive name/slug search. */
    @Query("{ 'deleted': false, $or: [ { 'name': { $regex: ?0, $options: 'i' } }, { 'slug': { $regex: ?0, $options: 'i' } } ] }")
    List<Organization> searchByQuery(String q);
}
