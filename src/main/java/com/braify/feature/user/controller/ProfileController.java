package com.braify.feature.user.controller;

import com.braify.feature.storage.model.StoragePurpose;
import com.braify.feature.storage.model.StoredFile;
import com.braify.feature.storage.service.StorageService;
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
    private final StorageService storageService;
    private final com.braify.feature.auth.service.PasswordPolicyService passwordPolicyService;
    private final com.braify.feature.platform.service.PlatformSettingsService platformSettingsService;
    private final com.braify.feature.session.repository.UserSessionRepository sessionRepository;

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

        // Platform access policy: block self-editing when disabled (Platform Admins exempt).
        if (!platformSettingsService.getSettings().getAccess().isAllowProfileSelfEdit()
                && me(auth).getRole() != AppUser.Role.PLATFORM_ADMIN) {
            throw new RuntimeException("Profile editing has been disabled by your administrator.");
        }

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
                                                              Authentication auth,
                                                              jakarta.servlet.http.HttpServletRequest http) {
        log.info("PUT /api/profile/me/password caller='{}'", me(auth).getEmail());
        String currentPassword = body.get("currentPassword");
        String newPassword     = body.get("newPassword");

        if (currentPassword == null || newPassword == null) {
            log.warn("Password change rejected for '{}': missing currentPassword or newPassword", me(auth).getEmail());
            throw new RuntimeException("currentPassword and newPassword are required");
        }

        AppUser user = userRepository.findById(me(auth).getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            log.warn("Password change rejected for '{}': current password mismatch", user.getEmail());
            throw new RuntimeException("Current password is incorrect");
        }

        // Enforce platform password policy (length / complexity / re-use) + history.
        passwordPolicyService.applyNewPassword(user, newPassword);
        user.setMustChangePassword(false);
        // Changed inside a session that proved the mailbox (email code) → the new password is the
        // user's own. Otherwise leave the flag as-is: whoever knew the old password may be changing it.
        Object jti = http.getAttribute(com.braify.config.RequestLoggingFilter.SESSION_ID_ATTR);
        if (jti instanceof String j && sessionRepository.findByJtiAndActiveTrue(j)
                .map(com.braify.feature.session.model.UserSession::isEmailVerified).orElse(false)) {
            user.setPasswordMailboxVerified(true);
        }
        userRepository.save(user);

        auditLogService.log(
                user.getId(), user.getEmail(),
                AuditLog.Action.PASSWORD_CHANGED, AuditLog.ResourceType.USER,
                0, null, user.getEmail());

        log.info("Password changed for user '{}'", user.getEmail());
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    /**
     * POST /api/profile/me/avatar — set or remove the profile picture.
     * Body: {@code { "fileId": "<id from POST /api/storage/upload?purpose=AVATAR>" }};
     * a null/blank fileId removes the picture. The previous picture is deleted from storage.
     */
    @PostMapping("/me/avatar")
    public ResponseEntity<UserResponse> uploadAvatar(@RequestBody Map<String, String> body,
                                                     Authentication auth) {
        AppUser caller = me(auth);
        String fileId = body.get("fileId") != null ? body.get("fileId").trim() : "";
        log.info("POST /api/profile/me/avatar caller='{}' fileId='{}'", caller.getEmail(), fileId);

        AppUser user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        String previous = user.getProfilePictureFileId();
        String legacyDataUrl = body.get("avatar");   // older clients send a data URL; it is stored like an upload
        boolean unchanged = fileId.equals(previous != null ? previous : "")
                && legacyDataUrl == null && user.getProfilePicture() == null;
        if (unchanged) return ResponseEntity.ok(userService.toResponse(user));

        if (fileId.isEmpty() && legacyDataUrl != null && legacyDataUrl.startsWith("data:") && legacyDataUrl.contains(",")) {
            String type = legacyDataUrl.substring(5, legacyDataUrl.indexOf(legacyDataUrl.contains(";") ? ';' : ','));
            byte[] bytes = java.util.Base64.getDecoder().decode(legacyDataUrl.substring(legacyDataUrl.indexOf(',') + 1).trim());
            if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Avatar image too large (max 2 MB)");
            user.setProfilePictureFileId(storageService.store(caller.getOrganizationId(), StoragePurpose.AVATAR, bytes,
                    "avatar", type.isBlank() ? "image/png" : type, caller.getId(), "avatar:" + user.getId()).getId());
        } else if (!fileId.isEmpty()) {
            StoredFile upload = storageService.getForUser(fileId, StoragePurpose.AVATAR, caller);
            user.setProfilePictureFileId(storageService.claim(upload, "avatar:" + user.getId()).getId());
        } else {
            user.setProfilePictureFileId(null);
        }
        user.setProfilePicture(null);   // drop any legacy inline picture
        userRepository.save(user);
        storageService.deleteQuietly(previous);

        auditLogService.log(
                user.getId(), user.getEmail(),
                AuditLog.Action.AVATAR_UPDATED, AuditLog.ResourceType.USER,
                0, null, user.getEmail());

        log.info("Avatar {} for user '{}'", fileId.isEmpty() ? "removed" : "updated", user.getEmail());
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
