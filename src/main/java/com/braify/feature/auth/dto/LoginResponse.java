package com.braify.feature.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {
    private String token;
    private String userId;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    private String organizationId;
    private String organizationName;
    private String profilePicture;
    private boolean mustChangePassword;

    /**
     * Feature keys enabled for this user's organisation.
     * Frontend uses this to show/hide modules immediately after login.
     * PLATFORM_ADMIN users receive all feature keys.
     */
    private List<String> features;
}
