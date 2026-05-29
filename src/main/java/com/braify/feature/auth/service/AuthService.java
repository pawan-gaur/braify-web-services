package com.braify.feature.auth.service;

import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.auth.dto.LoginRequest;
import com.braify.feature.auth.dto.LoginResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.shared.Feature;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.session.model.UserSession;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.session.repository.UserSessionRepository;
import com.braify.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository     userRepository;
    private final UserSessionRepository sessionRepository;
    private final OrganizationRepository orgRepository;
    private final PasswordEncoder       passwordEncoder;
    private final JwtUtil               jwtUtil;
    private final AuditLogService       auditLogService;

    private static final int MAX_SESSIONS = 3;

    public LoginResponse login(LoginRequest req, String ipAddress) {
        log.info("Login attempt for email='{}'", req.getEmail());
        AppUser user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> {
                    log.warn("Login failed — user not found for email='{}'", req.getEmail());
                    return new RuntimeException("Invalid email or password");
                });

        if (!user.isActive()) {
            log.warn("Login rejected — account disabled for email='{}'", req.getEmail());
            throw new RuntimeException("Account is disabled");
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.warn("Login failed — invalid password for email='{}'", req.getEmail());
            throw new RuntimeException("Invalid email or password");
        }

        // Enforce session limit: if >= MAX_SESSIONS active, revoke oldest
        List<UserSession> activeSessions =
                sessionRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId());
        if (activeSessions.size() >= MAX_SESSIONS) {
            int toRevoke = activeSessions.size() - MAX_SESSIONS + 1;
            log.info("Session limit reached for user '{}' — revoking {} oldest session(s)", user.getEmail(), toRevoke);
            activeSessions.stream().limit(toRevoke).forEach(s -> {
                s.setActive(false);
                sessionRepository.save(s);
            });
        }

        // Generate token and persist session
        String token = jwtUtil.generateToken(user);
        String jti   = jwtUtil.extractJti(token);

        UserSession session = UserSession.builder()
                .userId(user.getId())
                .jti(jti)
                .organizationId(user.getOrganizationId())
                .userRole(user.getRole().name())
                .deviceInfo(req.getDeviceInfo())
                .ipAddress(ipAddress)
                .active(true)
                .expiresAt(jwtUtil.expiresAt(token))
                .lastUsedAt(LocalDateTime.now())
                .build();
        sessionRepository.save(session);

        // Audit: record login event against SESSION resource
        auditLogService.logByUser(
                session.getId(), null,
                AuditLog.Action.LOGIN, AuditLog.ResourceType.SESSION,
                0, Map.of("ip",         ipAddress  != null ? ipAddress : "unknown",
                           "deviceInfo", req.getDeviceInfo() != null ? req.getDeviceInfo() : ""),
                user);

        // Fetch org (name + features) if applicable
        Organization org = user.getOrganizationId() != null
                ? orgRepository.findById(user.getOrganizationId()).orElse(null)
                : null;

        String orgName = org != null ? org.getName() : null;

        // PLATFORM_ADMIN sees all features; regular users see their org's assigned features
        boolean isPlatformAdmin = user.getRole() == AppUser.Role.PLATFORM_ADMIN;
        List<String> features = isPlatformAdmin
                ? Feature.allKeys()
                : (org != null && org.getFeatures() != null ? org.getFeatures() : List.of());

        log.info("Login successful for user '{}' (role={})", user.getEmail(), user.getRole());
        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole().name())
                .organizationId(user.getOrganizationId())
                .organizationName(orgName)
                .profilePicture(user.getProfilePicture())
                .mustChangePassword(user.isMustChangePassword())
                .features(features)
                .build();
    }

    public void logout(String jti) {
        log.info("Logout: revoking session jti='{}'", jti);
        sessionRepository.findByJtiAndActiveTrue(jti).ifPresent(s -> {
            s.setActive(false);
            sessionRepository.save(s);
            log.info("Session revoked for user '{}'", s.getUserId());
            // Audit: record logout — look up user once for full details
            userRepository.findById(s.getUserId()).ifPresent(u ->
                auditLogService.logByUser(
                        s.getId(), null,
                        AuditLog.Action.LOGOUT, AuditLog.ResourceType.SESSION,
                        0, null, u)
            );
        });
    }
}
