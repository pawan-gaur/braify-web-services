package com.braify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
}
