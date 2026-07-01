package com.braify.config;

import com.braify.security.ApiKeyAuthFilter;
import com.braify.security.JwtAuthFilter;
import com.braify.security.UserDetailsServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.Customizer;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter    jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final UserDetailsServiceImpl userDetailsService;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // MFA management requires an authenticated session (more specific rule first).
                // Note: /api/auth/login/mfa is NOT matched here — it stays public (the login challenge).
                .requestMatchers("/api/auth/mfa/**").authenticated()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/esign/sign/**").permitAll()   // client signing (ESIGN token)
                .requestMatchers("/api/esign/view/**").permitAll()   // read-only CC viewer (ESIGN_VIEW token)
                .requestMatchers("/api/esign/verify/**").permitAll() // public verification
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/onboarding").permitAll() // public get-started form
                // OpenAPI / Swagger UI — allow unauthenticated access so the frontend doc page can load the spec
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated()
            )
            // Register JwtAuthFilter first so its class is recorded in Spring Security's
            // internal filter-order map.  Then ApiKeyAuthFilter is inserted before it —
            // referencing a class that is now known to the order registry.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthFilter, JwtAuthFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
