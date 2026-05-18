package com.braify.feature.user.dto;

import lombok.Data;

@Data
public class UserRequest {
    private String email;
    private String password;      // optional — omit to auto-generate & send invite
    private String firstName;
    private String lastName;
    /** "ORG_ADMIN", "ADMIN", "USER" */
    private String role;
    private String organizationId;
    /** When true (default), an email invite is sent to set the password. */
    private boolean sendInvite = true;
}
