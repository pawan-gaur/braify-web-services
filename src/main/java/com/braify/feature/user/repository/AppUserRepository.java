package com.braify.feature.user.repository;

import com.braify.feature.user.model.AppUser;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppUserRepository extends MongoRepository<AppUser, String> {

    Optional<AppUser> findByEmail(String email);

    /** Active only — used for session scope, counts, etc. */
    List<AppUser> findByOrganizationIdAndActiveTrue(String organizationId);
    List<AppUser> findAllByActiveTrue();

    /** All (active + inactive) — used for user management pages */
    List<AppUser> findByOrganizationId(String organizationId);

    /** Search by name/email across all users (platform admin) — includes inactive. */
    @Query("{ $or: [ { 'email': { $regex: ?0, $options: 'i' } }, { 'firstName': { $regex: ?0, $options: 'i' } }, { 'lastName': { $regex: ?0, $options: 'i' } } ] }")
    List<AppUser> searchAllByQuery(String q);

    /** Search within a specific org — includes inactive. */
    @Query("{ 'organizationId': ?1, $or: [ { 'email': { $regex: ?0, $options: 'i' } }, { 'firstName': { $regex: ?0, $options: 'i' } }, { 'lastName': { $regex: ?0, $options: 'i' } } ] }")
    List<AppUser> searchByOrgAndQuery(String q, String organizationId);

    List<AppUser> findByOrganizationIdAndActiveTrueOrderByCreatedAtDesc(String organizationId);

    /** Counts */
    long countByActiveTrue();
    long countByOrganizationIdAndActiveTrue(String organizationId);
    long countByOrganizationIdAndActiveTrueAndMustChangePasswordTrue(String organizationId);
    long countByActiveTrueAndMustChangePasswordTrue();

    /** Monthly growth */
    long countByActiveTrueAndCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
    long countByOrganizationIdAndActiveTrueAndCreatedAtBetween(String organizationId, java.time.LocalDateTime from, java.time.LocalDateTime to);

    /** Session listing: ADMIN sees only ADMIN + USER within their org */
    List<AppUser> findByOrganizationIdAndActiveTrueAndRoleIn(String organizationId, List<AppUser.Role> roles);
}
