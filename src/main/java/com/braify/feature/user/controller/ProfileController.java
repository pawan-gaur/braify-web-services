package com.braify.feature.user.controller;

import com.braify.feature.user.dto.UserResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Self-service profile management — any authenticated user.
 * All changes are audit-logged under ResourceType.USER.
 */
@Slf4j
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final UserService userService;

    private AppUser me(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    /** GET /api/profile/me — return current user's full profile */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getProfile(Authentication auth) {
        log.debug("GET /api/profile/me caller='{}'", me(auth).getEmail());
        return ResponseEntity.ok(userService.toResponse(me(auth)));
    }

    /** PUT /api/profile/me — update basic info (firstName, lastName, bio) */
    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(@RequestBody Map<String, String> body,
                                                      Authentication auth) {
        log.info("PUT /api/profile/me caller='{}'", me(auth).getEmail());
        AppUser user = userRepository.findById(me(auth).getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (body.containsKey("firstName")) user.setFirstName(body.get("firstName"));
        if (body.containsKey("lastName"))  user.setLastName(body.get("lastName"));
        if (body.containsKey("bio"))       user.setBio(body.get("bio"));

        userRepository.save(user);

        auditLogService.log(
                user.getId(), user.getEmail(),
                AuditLog.Action.UPDATED, AuditLog.ResourceType.USER,
                0, null, user.getEmail());

        log.info("Profile updated for user '{}'", user.getEmail());
        return ResponseEntity.ok(userService.toResponse(user));
    }

    /** PUT /api/profile/me/password — change own password (requires current password) */
    @PutMapping("/me/password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody Map<String, String> body,
                                                              Authentication auth) {
        log.info("PUT /api/profile/me/password caller='{}'", me(auth).getEmail());
        String currentPassword = body.get("currentPassword");
        String newPassword     = body.get("newPassword");

        if (currentPassword == null || newPassword == null || newPassword.length() < 6) {
            log.warn("Password change rejected for '{}': missing or too-short newPassword", me(auth).getEmail());
            throw new RuntimeException("currentPassword and a newPassword of at least 6 characters are required");
        }

        AppUser user = userRepository.findById(me(auth).getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            log.warn("Password change rejected for '{}': current password mismatch", user.getEmail());
            throw new RuntimeException("Current password is incorrect");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);

        auditLogService.log(
                user.getId(), user.getEmail(),
                AuditLog.Action.PASSWORD_CHANGED, AuditLog.ResourceType.USER,
                0, null, user.getEmail());

        log.info("Password changed for user '{}'", user.getEmail());
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    /**
     * POST /api/profile/me/avatar — upload profile picture.
     * Body: { "avatar": "<base64 data-URL>" }
     * The client should encode the image as:  "data:image/jpeg;base64,..."
     */
    @PostMapping("/me/avatar")
    public ResponseEntity<UserResponse> uploadAvatar(@RequestBody Map<String, String> body,
                                                     Authentication auth) {
        log.info("POST /api/profile/me/avatar caller='{}'", me(auth).getEmail());
        String avatar = body.get("avatar");
        if (avatar == null || avatar.isBlank()) {
            log.warn("Avatar upload rejected for '{}': no avatar data provided", me(auth).getEmail());
            throw new RuntimeException("No avatar data provided");
        }
        // Basic size guard — base64 of ~2 MB is ~2.7 MB string
        if (avatar.length() > 3_000_000) {
            throw new RuntimeException("Avatar image too large (max ~2 MB)");
        }

        AppUser user = userRepository.findById(me(auth).getId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setProfilePicture(avatar);
        userRepository.save(user);

        auditLogService.log(
                user.getId(), user.getEmail(),
                AuditLog.Action.AVATAR_UPDATED, AuditLog.ResourceType.USER,
                0, null, user.getEmail());

        log.info("Avatar updated for user '{}'", user.getEmail());
        return ResponseEntity.ok(userService.toResponse(user));
    }

    /**
     * GET /api/profile/me/audit — view own profile audit trail.
     */
    @GetMapping("/me/audit")
    public ResponseEntity<?> myAuditLog(Authentication auth) {
        AppUser user = me(auth);
        log.debug("GET /api/profile/me/audit caller='{}'", user.getEmail());
        return ResponseEntity.ok(auditLogService.getForResource(user.getId()));
    }
}
