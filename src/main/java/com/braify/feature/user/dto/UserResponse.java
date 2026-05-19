package com.braify.feature.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserResponse {
    private String id;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    private String organizationId;
    private String organizationName;
    private boolean active;
    private boolean mustChangePassword;
    private String profilePicture;
    private String bio;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Feature keys enabled for this user's organisation.
     * Always populated from Organization.features.
     * PLATFORM_ADMIN users receive all feature keys.
     */
    private List<String> features;

    /**
     * Per-feature role whitelist from the organisation's branding config.
     * Key = feature key (e.g. "PDF_TEMPLATES"), value = list of role names allowed.
     * Null when no role restrictions have been configured (all roles have access).
     * PLATFORM_ADMIN users receive null (they bypass all restrictions).
     */
    private Map<String, List<String>> featureRoleAccess;

    /**
     * Organisation primary brand colour (#rrggbb).
     * Used to apply the org's theme to the web app UI.
     */
    private String primaryColor;

    /**
     * Organisation accent/secondary brand colour (#rrggbb).
     */
    private String accentColor;
}
