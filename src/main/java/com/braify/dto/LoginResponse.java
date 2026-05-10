package com.braify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
