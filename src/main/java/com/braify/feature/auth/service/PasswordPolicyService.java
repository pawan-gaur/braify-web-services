package com.braify.feature.auth.service;

import com.braify.feature.platform.model.PlatformSettings;
import com.braify.feature.platform.service.PlatformSettingsService;
import com.braify.feature.user.model.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Enforces the platform-wide password policy (see {@link PlatformSettings.Password}):
 * minimum length, character-class complexity, re-use restriction and expiry.
 *
 * <p>Call {@link #validate} to check a raw password before accepting it, or
 * {@link #applyNewPassword} to validate + encode + update password history in one step.
 * Validation failures throw {@link IllegalArgumentException} → mapped to HTTP 400 with the
 * message by {@code GlobalExceptionHandler}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordPolicyService {

    private final PlatformSettingsService platformSettingsService;
    private final PasswordEncoder         passwordEncoder;

    private PlatformSettings.Password policy() {
        PlatformSettings.Security sec = platformSettingsService.getSettings().getSecurity();
        return sec != null && sec.getPassword() != null
                ? sec.getPassword()
                : PlatformSettings.Password.builder().build();
    }

    /**
     * Validates a raw password against the current policy.
     * When {@code user} is non-null and re-use restriction is on, also rejects any
     * password matching the user's current or recent previous passwords.
     *
     * @throws IllegalArgumentException with a human-readable message if any rule fails.
     */
    public void validate(String raw, AppUser user) {
        PlatformSettings.Password p = policy();
        List<String> problems = new ArrayList<>();

        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Password is required.");
        }
        if (raw.length() < p.getMinLength()) {
            problems.add("at least " + p.getMinLength() + " characters");
        }
        if (p.isRequireUpper()  && !raw.chars().anyMatch(Character::isUpperCase)) problems.add("an uppercase letter");
        if (p.isRequireLower()  && !raw.chars().anyMatch(Character::isLowerCase)) problems.add("a lowercase letter");
        if (p.isRequireDigit()  && !raw.chars().anyMatch(Character::isDigit))     problems.add("a number");
        if (p.isRequireSymbol() && raw.chars().allMatch(Character::isLetterOrDigit)) problems.add("a symbol");

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Password must contain " + String.join(", ", problems) + ".");
        }

        if (p.isReuseRestriction() && user != null && matchesRecent(raw, user)) {
            throw new IllegalArgumentException(
                    "You can't reuse a recent password. Choose one you haven't used before.");
        }
    }

    /**
     * Validates the new password, then updates the user in memory: pushes the old hash
     * into the re-use history (capped), sets the new encoded password and stamps
     * {@code passwordChangedAt}. The caller is responsible for persisting the user.
     */
    public void applyNewPassword(AppUser user, String raw) {
        validate(raw, user);

        PlatformSettings.Password p = policy();
        // Keep enough history to enforce "block last N" (current + N-1 previous = N total).
        int keep = Math.max(0, p.getReuseCount() - 1);

        List<String> history = new ArrayList<>();
        if (user.getPassword() != null) history.add(user.getPassword()); // the outgoing password
        if (user.getPreviousPasswords() != null) history.addAll(user.getPreviousPasswords());
        if (history.size() > keep) history = new ArrayList<>(history.subList(0, keep));

        user.setPreviousPasswords(history);
        user.setPassword(passwordEncoder.encode(raw));
        user.setPasswordChangedAt(LocalDateTime.now());
    }

    /** True when the user's password has exceeded the policy's expiry window. */
    public boolean isExpired(AppUser user) {
        PlatformSettings.Password p = policy();
        if (p.getExpiryDays() <= 0 || user.getPasswordChangedAt() == null) return false;
        return user.getPasswordChangedAt().plusDays(p.getExpiryDays()).isBefore(LocalDateTime.now());
    }

    private boolean matchesRecent(String raw, AppUser user) {
        if (user.getPassword() != null && passwordEncoder.matches(raw, user.getPassword())) return true;
        if (user.getPreviousPasswords() != null) {
            for (String hash : user.getPreviousPasswords()) {
                if (hash != null && passwordEncoder.matches(raw, hash)) return true;
            }
        }
        return false;
    }
}
