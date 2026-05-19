package com.braify.feature.auth.service;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final OrganizationRepository orgRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    private static final int MAX_SESSIONS = 3;

    public LoginResponse login(LoginRequest req, String ipAddress) {
        AppUser user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        if (!user.isActive()) {
            throw new RuntimeException("Account is disabled");
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid email or password");
        }

        // Enforce session limit: if >= MAX_SESSIONS active, revoke oldest
        List<UserSession> activeSessions =
                sessionRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId());
        if (activeSessions.size() >= MAX_SESSIONS) {
            int toRevoke = activeSessions.size() - MAX_SESSIONS + 1;
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
        sessionRepository.findByJtiAndActiveTrue(jti).ifPresent(s -> {
            s.setActive(false);
            sessionRepository.save(s);
        });
    }
}
