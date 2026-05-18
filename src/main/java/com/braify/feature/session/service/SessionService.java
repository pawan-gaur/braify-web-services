package com.braify.feature.session.service;

import com.braify.feature.session.dto.SessionResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.session.model.UserSession;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.session.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final UserSessionRepository  sessionRepository;
    private final AppUserRepository      userRepository;
    private final OrganizationRepository orgRepository;
    private final AuditLogService        auditLogService;

    // ── Role rank ─────────────────────────────────────────────────────────────

    private static final Map<String, Integer> ROLE_RANK = Map.of(
            "PLATFORM_ADMIN", 4,
            "ORG_ADMIN",      3,
            "ADMIN",          2,
            "USER",           1
    );

    private int rank(String role) {
        return ROLE_RANK.getOrDefault(role, 0);
    }

    private int callerRank(AppUser caller) {
        return rank(caller.getRole().name());
    }

    /**
     * Resolves the role rank of a session's owner.
     * Falls back to a DB lookup for sessions created before the {@code userRole}
     * field was introduced (avoids the null → rank 0 bypass bug).
     */
    private int sessionOwnerRank(UserSession session) {
        if (session.getUserRole() != null && !session.getUserRole().isBlank()) {
            return rank(session.getUserRole());
        }
        return userRepository.findById(session.getUserId())
                .map(u -> rank(u.getRole().name()))
                .orElse(1);   // safest assumption: USER
    }

    // ── List sessions (strict role hierarchy) ────────────────────────────────

    /**
     * Returns active sessions visible to {@code caller} based on strict hierarchy:
     * <ul>
     *   <li>PLATFORM_ADMIN → every active session on the platform</li>
     *   <li>ORG_ADMIN      → own + ADMIN + USER sessions within their org</li>
     *   <li>ADMIN          → own + USER sessions within their org</li>
     *   <li>USER           → own sessions only</li>
     * </ul>
     * Each role can see its own sessions plus those of roles strictly below it.
     *
     * @param currentJti JTI of the caller's current token (marks the "Current" badge)
     */
    public List<SessionResponse> listSessions(AppUser caller, String currentJti) {
        List<UserSession> sessions;

        switch (caller.getRole()) {
            case PLATFORM_ADMIN ->
                sessions = sessionRepository.findAllByActiveTrueOrderByLastUsedAtDesc();

            case ORG_ADMIN -> {
                // Sees own sessions + ADMIN + USER sessions in their org
                List<String> ids = userIdsForRoles(caller.getOrganizationId(),
                        List.of(AppUser.Role.ADMIN, AppUser.Role.USER), caller.getId());
                sessions = sessionRepository.findByUserIdInAndActiveTrueOrderByLastUsedAtDesc(ids);
            }

            case ADMIN -> {
                // Sees own sessions + USER sessions in their org
                List<String> ids = userIdsForRoles(caller.getOrganizationId(),
                        List.of(AppUser.Role.USER), caller.getId());
                sessions = sessionRepository.findByUserIdInAndActiveTrueOrderByLastUsedAtDesc(ids);
            }

            default ->   // USER — own sessions only
                sessions = sessionRepository.findByUserIdAndActiveTrueOrderByLastUsedAtDesc(caller.getId());
        }

        return enrich(sessions, currentJti);
    }

    /** Collects user IDs for the given roles in the org, always including {@code ownerId}. */
    private List<String> userIdsForRoles(String orgId, List<AppUser.Role> roles, String ownerId) {
        List<String> ids = new ArrayList<>(
                userRepository.findByOrganizationIdAndActiveTrueAndRoleIn(orgId, roles)
                        .stream().map(AppUser::getId).toList());
        if (!ids.contains(ownerId)) {
            ids.add(ownerId);   // always include the caller's own sessions
        }
        return ids;
    }

    // ── Revoke a specific session ─────────────────────────────────────────────

    /**
     * Revokes the session with {@code sessionId}.
     * Enforces the same strict hierarchy as listing:
     * a caller can only revoke sessions owned by users with a strictly lower role rank.
     */
    public void revokeSession(String sessionId, AppUser caller, String currentJti) {
        UserSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        if (!session.isActive()) {
            throw new RuntimeException("Session is already inactive");
        }

        assertCanManage(caller, session);
        doRevoke(session, caller);

        auditLogService.log(
                session.getUserId(), "Session revoked",
                AuditLog.Action.SESSION_REVOKED, AuditLog.ResourceType.USER,
                0, null, caller.getEmail());
    }

    // ── Revoke all OTHER sessions of the caller ───────────────────────────────

    public int revokeAllMyOtherSessions(AppUser caller, String currentJti) {
        List<UserSession> mine = sessionRepository.findByUserIdAndActiveTrue(caller.getId());
        int count = 0;
        for (UserSession s : mine) {
            if (!s.getJti().equals(currentJti)) {
                doRevoke(s, caller);
                count++;
            }
        }
        return count;
    }

    // ── Bulk revoke on disable ────────────────────────────────────────────────

    /** Revokes every active session for {@code userId} (called by UserService on disable). */
    public void revokeAllForUser(String userId, AppUser revoker) {
        sessionRepository.findByUserIdAndActiveTrue(userId).forEach(s -> doRevoke(s, revoker));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void doRevoke(UserSession session, AppUser revoker) {
        session.setActive(false);
        session.setRevokedBy(revoker.getId());
        session.setRevokedByName(revoker.getFirstName() + " " + revoker.getLastName());
        session.setRevokedAt(LocalDateTime.now());
        sessionRepository.save(session);
    }

    /**
     * Strict hierarchy guard:
     * <ul>
     *   <li>PLATFORM_ADMIN → unrestricted</li>
     *   <li>ORG_ADMIN      → same org AND target rank &lt; 3 (i.e. ADMIN or USER)</li>
     *   <li>ADMIN          → same org AND target rank &lt; 2 (i.e. USER only)</li>
     *   <li>USER           → own sessions only</li>
     * </ul>
     */
    private void assertCanManage(AppUser caller, UserSession session) {
        switch (caller.getRole()) {
            case PLATFORM_ADMIN -> { /* unrestricted */ }

            case ORG_ADMIN, ADMIN -> {
                if (caller.getOrganizationId() == null ||
                    !caller.getOrganizationId().equals(session.getOrganizationId())) {
                    throw new RuntimeException("Access denied: session belongs to a different organization");
                }
                int targetRank = sessionOwnerRank(session);  // null-safe DB fallback
                if (targetRank >= callerRank(caller)) {
                    throw new RuntimeException("Access denied: you can only revoke sessions of users with a lower role");
                }
            }

            default -> {  // USER
                if (!caller.getId().equals(session.getUserId())) {
                    throw new RuntimeException("Access denied: you can only revoke your own sessions");
                }
            }
        }
    }

    // ── Enrichment ────────────────────────────────────────────────────────────

    private List<SessionResponse> enrich(List<UserSession> sessions, String currentJti) {
        List<String> userIds = sessions.stream().map(UserSession::getUserId).distinct().toList();
        Map<String, AppUser> userMap = userRepository.findAllById(userIds)
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));

        List<String> orgIds = sessions.stream()
                .map(UserSession::getOrganizationId)
                .filter(id -> id != null && !id.isBlank())
                .distinct().toList();
        Map<String, String> orgNameMap = orgRepository.findAllById(orgIds)
                .stream().collect(Collectors.toMap(Organization::getId, Organization::getName));

        return sessions.stream().map(s -> {
            AppUser u = userMap.get(s.getUserId());
            String orgName = s.getOrganizationId() != null
                    ? orgNameMap.getOrDefault(s.getOrganizationId(), s.getOrganizationId())
                    : null;
            // Resolve role: prefer stored userRole (fast), fallback to live user record
            String role = (s.getUserRole() != null && !s.getUserRole().isBlank())
                    ? s.getUserRole()
                    : (u != null ? u.getRole().name() : "");

            return SessionResponse.builder()
                    .id(s.getId())
                    .userId(s.getUserId())
                    .userName(u != null ? u.getFirstName() + " " + u.getLastName() : "Unknown")
                    .userEmail(u != null ? u.getEmail() : "")
                    .userRole(role)
                    .organizationId(s.getOrganizationId())
                    .organizationName(orgName)
                    .deviceInfo(s.getDeviceInfo())
                    .ipAddress(s.getIpAddress())
                    .createdAt(s.getCreatedAt())
                    .lastUsedAt(s.getLastUsedAt())
                    .expiresAt(s.getExpiresAt())
                    .current(s.getJti() != null && s.getJti().equals(currentJti))
                    .build();
        }).toList();
    }
}
