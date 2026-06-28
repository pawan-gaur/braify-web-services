package com.braify.config;

import com.braify.security.UserDetailsImpl;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Supplies the "who" for Spring Data Mongo {@code @CreatedBy} / {@code @LastModifiedBy}
 * auditing — populated automatically on every insert/update across all collections.
 *
 * <p>Returns the acting user's <b>userId</b> (matching the existing {@code createdBy}
 * convention used by ESign/file/template documents, so the same field can also be used
 * for access scoping). Falls back to:
 * <ul>
 *   <li>{@code "api-key:<name>"} — request authenticated via an organisation API key</li>
 *   <li>{@code "system"} — no authenticated principal (schedulers, seeders, public forms)</li>
 * </ul>
 */
@Component("auditorAware")
public class AuditorAwareImpl implements AuditorAware<String> {

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.of("system");

        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetailsImpl ud && ud.getId() != null) {
            return Optional.of(ud.getId());
        }

        String name = auth.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return Optional.of("system");
        }
        // API-key / non-user principals fall through to their authentication name.
        return Optional.of(name);
    }
}
