package com.braify.feature.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserRequest {
    @NotBlank @Email
    private String email;
    private String password;      // optional — omit to auto-generate & send invite
    @NotBlank
    private String firstName;
    @NotBlank
    private String lastName;
    /** "ORG_ADMIN", "ADMIN", "USER" */
    @NotBlank
    private String role;
    private String organizationId;
    /** When true (default), an email invite is sent to set the password. */
    private boolean sendInvite = true;
}
