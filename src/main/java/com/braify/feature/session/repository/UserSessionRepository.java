package com.braify.feature.session.repository;

import com.braify.feature.session.model.UserSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends MongoRepository<UserSession, String> {

    /* ── Auth service (session enforcement) ── */
    List<UserSession> findByUserIdAndActiveTrueOrderByCreatedAtAsc(String userId);
    Optional<UserSession> findByJtiAndActiveTrue(String jti);

    /** Refresh flow — resolve the active session that owns a given refresh-token hash. */
    Optional<UserSession> findByRefreshTokenHashAndActiveTrue(String refreshTokenHash);
    long countByUserIdAndActiveTrue(String userId);
    void deleteByJti(String jti);

    /* ── Session listing — role-scoped ── */

    /** PLATFORM_ADMIN: all active sessions */
    List<UserSession> findAllByActiveTrueOrderByLastUsedAtDesc();

    /** ORG_ADMIN: all sessions within their org */
    List<UserSession> findByOrganizationIdAndActiveTrueOrderByLastUsedAtDesc(String organizationId);

    /** ADMIN / USER: sessions for a specific set of userIds */
    List<UserSession> findByUserIdInAndActiveTrueOrderByLastUsedAtDesc(List<String> userIds);

    /** USER: own sessions only */
    List<UserSession> findByUserIdAndActiveTrueOrderByLastUsedAtDesc(String userId);

    /* ── Bulk revoke (when disabling a user) ── */
    List<UserSession> findByUserIdAndActiveTrue(String userId);

    /* ── Cleanup — inactive sessions older than a cutoff ── */
    long deleteByActiveFalseAndLastUsedAtBefore(LocalDateTime cutoff);
}
