package com.braify.config;

import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the first Platform Admin on an empty database. The credentials come from the
 * environment ({@code SEED_ADMIN_EMAIL} / {@code SEED_ADMIN_PASSWORD}) — never from source.
 * Does nothing when that admin already exists, so existing databases are never modified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.admin-email:admin@braify.com}")
    private String adminEmail;

    @Value("${app.seed.admin-password:}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        if (userRepository.findByEmail(adminEmail).isPresent()) return;

        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("No Platform Admin '{}' exists and SEED_ADMIN_PASSWORD is not set — skipping seed. "
                    + "Set SEED_ADMIN_EMAIL / SEED_ADMIN_PASSWORD and restart to create one.", adminEmail);
            return;
        }

        AppUser admin = AppUser.builder()
                .email(adminEmail)
                .password(passwordEncoder.encode(adminPassword))
                .firstName("Platform")
                .lastName("Admin")
                .role(AppUser.Role.PLATFORM_ADMIN)
                .active(true)
                .mustChangePassword(true)          // operator-supplied secret → rotate on first login
                .passwordMailboxVerified(false)    // known to whoever set the env var
                .build();
        userRepository.save(admin);
        log.info("Seeded Platform Admin '{}' — change the password after first sign-in.", adminEmail);
    }
}
