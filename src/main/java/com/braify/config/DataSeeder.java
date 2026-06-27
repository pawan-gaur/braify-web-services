package com.braify.config;

import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.findByEmail("admin@braify.com").isEmpty()) {
            AppUser admin = AppUser.builder()
                    .email("admin@braify.com")
                    .password(passwordEncoder.encode("Awas@317&"))
                    .firstName("Platform")
                    .lastName("Admin")
                    .role(AppUser.Role.PLATFORM_ADMIN)
                    .active(true)
                    .build();
            userRepository.save(admin);
        }
    }
}
