package com.braify.service;

import com.braify.dto.UserRequest;
import com.braify.dto.UserResponse;
import com.braify.model.AppUser;
import com.braify.model.AuditLog;
import com.braify.model.Feature;
import com.braify.model.Organization;
import com.braify.repository.AppUserRepository;
import com.braify.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository    userRepository;
    private final OrganizationRepository orgRepository;
    private final PasswordEncoder      passwordEncoder;
    private final EmailInviteService   emailInviteService;
    private final AuditLogService      auditLogService;
    private final SessionService       sessionService;
    private final QuotaService         quotaService;

    /**
     * Returns users visible to currentUser.
     * PLATFORM_ADMIN sees all; others see their org only.
     */
    /** Returns all users (active + inactive) visible to currentUser — used by the management UI. */
    public List<UserResponse> findAll(AppUser currentUser) {
        List<AppUser> users;
        if (currentUser.getRole() == AppUser.Role.PLATFORM_ADMIN) {
            users = userRepository.findAll();
        } else {
            users = userRepository.findByOrganizationId(currentUser.getOrganizationId());
        }
        return users.stream().map(this::toResponse).toList();
    }

    /**
     * Full-text search across name/email.
     * PLATFORM_ADMIN may pass orgId to scope; otherwise all orgs.
     */
    public List<UserResponse> search(String q, String orgId, AppUser currentUser) {
        List<AppUser> results;
        if (currentUser.getRole() == AppUser.Role.PLATFORM_ADMIN) {
            if (orgId != null && !orgId.isBlank()) {
                results = userRepository.searchByOrgAndQuery(q, orgId);
            } else {
                results = userRepository.searchAllByQuery(q);
            }
        } else {
            results = userRepository.searchByOrgAndQuery(q, currentUser.getOrganizationId());
        }
        return results.stream().map(this::toResponse).toList();
    }

    public UserResponse findById(String id, AppUser currentUser) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        assertSameOrg(currentUser, user.getOrganizationId());
        return toResponse(user);
    }

    public UserResponse create(UserRequest req, AppUser currentUser) {
        if (userRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new RuntimeException("Email already registered: " + req.getEmail());
        }

        AppUser.Role role = AppUser.Role.valueOf(req.getRole());

        // Only PLATFORM_ADMIN can create PLATFORM_ADMIN users
        if (role == AppUser.Role.PLATFORM_ADMIN && currentUser.getRole() != AppUser.Role.PLATFORM_ADMIN) {
            throw new RuntimeException("Insufficient permissions to create a Platform Admin");
        }

        // Determine organization
        String orgId = currentUser.getRole() == AppUser.Role.PLATFORM_ADMIN
                ? req.getOrganizationId()
                : currentUser.getOrganizationId();

        // Enforce user quota (skipped for PLATFORM_ADMIN who have no orgId)
        quotaService.checkUserCount(orgId);

        // Use provided password or generate a random one (invite flow)
        boolean sendInvite = req.isSendInvite();
        String rawPassword = (req.getPassword() != null && !req.getPassword().isBlank())
                ? req.getPassword()
                : UUID.randomUUID().toString();

        AppUser user = AppUser.builder()
                .email(req.getEmail())
                .password(passwordEncoder.encode(rawPassword))
                .firstName(req.getFirstName())
                .lastName(req.getLastName())
                .role(role)
                .organizationId(orgId)
                .active(true)
                .mustChangePassword(sendInvite) // must reset via invite link
                .build();
        user = userRepository.save(user);

        // Audit
        auditLogService.log(
                user.getId(), user.getEmail(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.USER,
                0, null, currentUser.getEmail(), user.getOrganizationId());

        // Send invite email asynchronously (fire-and-forget; errors are logged)
        if (sendInvite) {
            emailInviteService.sendInvite(user);
        }

        return toResponse(user);
    }

    public UserResponse update(String id, UserRequest req, AppUser currentUser) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        assertSameOrg(currentUser, user.getOrganizationId());

        user.setFirstName(req.getFirstName());
        user.setLastName(req.getLastName());
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }
        UserResponse response = toResponse(userRepository.save(user));

        auditLogService.log(
                user.getId(), user.getEmail(),
                AuditLog.Action.UPDATED, AuditLog.ResourceType.USER,
                0, null, currentUser.getEmail(), user.getOrganizationId());

        return response;
    }

    public void deactivate(String id, AppUser currentUser) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        assertSameOrg(currentUser, user.getOrganizationId());
        assertCanManage(currentUser, user);

        user.setActive(false);
        userRepository.save(user);

        // Revoke all active sessions for the disabled user
        sessionService.revokeAllForUser(user.getId(), currentUser);

        auditLogService.log(
                user.getId(), user.getEmail(),
                AuditLog.Action.DEACTIVATED, AuditLog.ResourceType.USER,
                0, null, currentUser.getEmail(), user.getOrganizationId());
    }

    public void enable(String id, AppUser currentUser) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        assertSameOrg(currentUser, user.getOrganizationId());
        assertCanManage(currentUser, user);

        user.setActive(true);
        userRepository.save(user);

        auditLogService.log(
                user.getId(), user.getEmail(),
                AuditLog.Action.ACTIVATED, AuditLog.ResourceType.USER,
                0, null, currentUser.getEmail(), user.getOrganizationId());
    }

    /**
     * Ensures the caller has sufficient role authority over the target user.
     * PLATFORM_ADMIN can manage anyone. Others can only manage users with a
     * strictly lower role rank.
     */
    private void assertCanManage(AppUser caller, AppUser target) {
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (caller.getId().equals(target.getId())) {
            throw new RuntimeException("You cannot enable/disable your own account");
        }
        int callerRank = roleRank(caller.getRole());
        int targetRank = roleRank(target.getRole());
        if (targetRank >= callerRank) {
            throw new RuntimeException("Insufficient permissions to manage this user");
        }
    }

    private int roleRank(AppUser.Role role) {
        return switch (role) {
            case PLATFORM_ADMIN -> 4;
            case ORG_ADMIN      -> 3;
            case ADMIN          -> 2;
            case USER           -> 1;
        };
    }

    private void assertSameOrg(AppUser currentUser, String targetOrgId) {
        if (currentUser.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (!currentUser.getOrganizationId().equals(targetOrgId)) {
            throw new RuntimeException("Access denied: different organization");
        }
    }

    public UserResponse toResponse(AppUser u) {
        // PLATFORM_ADMIN bypasses feature and role restrictions — return all keys
        boolean isPlatformAdmin = u.getRole() == AppUser.Role.PLATFORM_ADMIN;

        Organization org = u.getOrganizationId() != null
                ? orgRepository.findById(u.getOrganizationId()).orElse(null)
                : null;

        String orgName = org != null ? org.getName() : null;

        List<String> features = isPlatformAdmin
                ? Feature.allKeys()
                : (org != null && org.getFeatures() != null ? org.getFeatures() : List.of());

        // Pull branding fields (null-safe) — Platform Admin gets no restrictions
        com.braify.model.OrgBranding branding = (org != null) ? org.getBranding() : null;

        java.util.Map<String, List<String>> featureRoleAccess = (!isPlatformAdmin && branding != null)
                ? branding.getFeatureRoleAccess()
                : null;

        String primaryColor = branding != null ? branding.getPrimaryColor() : null;
        String accentColor  = branding != null ? branding.getAccentColor()  : null;

        return UserResponse.builder()
                .id(u.getId())
                .email(u.getEmail())
                .firstName(u.getFirstName())
                .lastName(u.getLastName())
                .role(u.getRole().name())
                .organizationId(u.getOrganizationId())
                .organizationName(orgName)
                .active(u.isActive())
                .mustChangePassword(u.isMustChangePassword())
                .profilePicture(u.getProfilePicture())
                .bio(u.getBio())
                .createdAt(u.getCreatedAt())
                .updatedAt(u.getUpdatedAt())
                .features(features)
                .featureRoleAccess(featureRoleAccess)
                .primaryColor(primaryColor)
                .accentColor(accentColor)
                .build();
    }
}
